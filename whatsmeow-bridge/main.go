// whatsmeow-bridge — minimal stdio bridge between ZeroClaw Android and the
// whatsmeow library (https://github.com/tulir/whatsmeow).
//
// Compiled to app/src/main/jniLibs/arm64-v8a/libwhatsmeow.so and spawned by
// WhatsAppNativeManager.kt as a child process. Build instructions live in BUILD.md.
//
// Protocol (line-based, UTF-8):
//   stdin  ← commands from Kotlin
//     PAIR <e164_phone>      request an 8-digit pair code for the given phone
//     SEND <jid> <b64_text>  send a text message (text base64-encoded)
//     STOP                   graceful shutdown
//
//   stdout → events to Kotlin
//     STATUS connecting | qr_ready | pair_ready | connected <jid> | disconnected | error <msg>
//     QR <text>                       raw QR payload (Kotlin renders the image)
//     PAIRCODE <code>                 8-digit pair code for the requested phone
//     MSG <jid> <pushname_b64> <text_b64>   incoming text message
//     LOG <text>                      diagnostic
//
// The first line of every event is a single token followed by space-delimited
// fields. Anything that may contain spaces / newlines is base64-encoded.

package main

import (
	"bufio"
	"context"
	"encoding/base64"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	_ "github.com/mattn/go-sqlite3"

	"go.mau.fi/whatsmeow"
	waE2E "go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/store/sqlstore"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	waLog "go.mau.fi/whatsmeow/util/log"
)

var (
	emit   = make(chan string, 64)
	stopCh = make(chan struct{})
	once   sync.Once
)

func emitLine(s string) {
	select {
	case emit <- s:
	default:
		// drop on backpressure rather than blocking the WhatsApp event loop
	}
}

func b64(s string) string { return base64.StdEncoding.EncodeToString([]byte(s)) }

func decodeB64(s string) string {
	out, err := base64.StdEncoding.DecodeString(s)
	if err != nil {
		return ""
	}
	return string(out)
}

// emitter goroutine — serialize all writes to stdout.
func emitter() {
	w := bufio.NewWriter(os.Stdout)
	defer w.Flush()
	flushTicker := time.NewTicker(200 * time.Millisecond)
	defer flushTicker.Stop()
	for {
		select {
		case line, ok := <-emit:
			if !ok {
				return
			}
			fmt.Fprintln(w, line)
			w.Flush()
		case <-flushTicker.C:
			w.Flush()
		case <-stopCh:
			return
		}
	}
}

func sessionDir() string {
	if d := os.Getenv("WHATSMEOW_DATA_DIR"); d != "" {
		return d
	}
	if d, err := os.UserHomeDir(); err == nil {
		return filepath.Join(d, ".zeroclaw", "whatsmeow")
	}
	return "."
}

func main() {
	dataDir := sessionDir()
	if err := os.MkdirAll(dataDir, 0o700); err != nil {
		fmt.Println("STATUS error " + b64("mkdir: "+err.Error()))
		return
	}
	dbPath := filepath.Join(dataDir, "session.db")

	go emitter()
	defer once.Do(func() { close(stopCh) })

	logger := waLog.Stdout("WAS", "INFO", false)
	_ = logger // whatsmeow takes its own logger; we route diagnostics via emit

	dbLog := waLog.Noop
	container, err := sqlstore.New(context.Background(), "sqlite3", "file:"+dbPath+"?_foreign_keys=on", dbLog)
	if err != nil {
		emitLine("STATUS error " + b64("sqlstore: "+err.Error()))
		return
	}
	deviceStore, err := container.GetFirstDevice(context.Background())
	if err != nil {
		emitLine("STATUS error " + b64("device: "+err.Error()))
		return
	}

	clientLog := waLog.Noop
	client := whatsmeow.NewClient(deviceStore, clientLog)

	client.AddEventHandler(func(evt interface{}) {
		switch v := evt.(type) {
		case *events.Connected:
			jid := ""
			if client.Store.ID != nil {
				jid = client.Store.ID.String()
			}
			emitLine("STATUS connected " + jid)
		case *events.LoggedOut:
			emitLine("STATUS disconnected")
		case *events.Disconnected:
			emitLine("STATUS disconnected")
		case *events.Message:
			handleIncoming(v)
		case *events.PairSuccess:
			emitLine("STATUS pair_success " + v.ID.String())
		}
	})

	// Start the QR/connect flow.
	startCtx, startCancel := context.WithCancel(context.Background())
	defer startCancel()

	if client.Store.ID == nil {
		// New device — emit QR codes until paired.
		qrChan, err := client.GetQRChannel(startCtx)
		if err != nil {
			emitLine("STATUS error " + b64("qr_channel: "+err.Error()))
			return
		}
		emitLine("STATUS connecting")
		if err := client.Connect(); err != nil {
			emitLine("STATUS error " + b64("connect: "+err.Error()))
			return
		}
		go func() {
			for evt := range qrChan {
				switch evt.Event {
				case "code":
					emitLine("STATUS qr_ready")
					emitLine("QR " + evt.Code)
				case "success":
					emitLine("STATUS pair_success_qr")
				case "timeout":
					emitLine("STATUS error " + b64("qr_timeout"))
				case "err-client-outdated":
					emitLine("STATUS error " + b64("client_outdated"))
				}
			}
		}()
	} else {
		// Returning user — straight reconnect.
		emitLine("STATUS connecting")
		if err := client.Connect(); err != nil {
			emitLine("STATUS error " + b64("reconnect: "+err.Error()))
			return
		}
	}

	// Read commands from stdin until STOP / EOF.
	stdin := bufio.NewScanner(os.Stdin)
	stdin.Buffer(make([]byte, 0, 64*1024), 4*1024*1024)
	for stdin.Scan() {
		line := strings.TrimSpace(stdin.Text())
		if line == "" {
			continue
		}
		switch {
		case line == "STOP":
			client.Disconnect()
			emitLine("STATUS disconnected")
			return
		case strings.HasPrefix(line, "PAIR "):
			handlePair(client, strings.TrimPrefix(line, "PAIR "))
		case strings.HasPrefix(line, "SEND "):
			handleSend(client, strings.TrimPrefix(line, "SEND "))
		default:
			emitLine("LOG " + b64("unknown_cmd: "+line))
		}
	}
	if err := stdin.Err(); err != nil {
		emitLine("LOG " + b64("stdin: "+err.Error()))
	}
	client.Disconnect()
}

func handlePair(client *whatsmeow.Client, phone string) {
	phone = strings.TrimSpace(phone)
	if phone == "" {
		emitLine("STATUS error " + b64("pair: empty phone"))
		return
	}
	if client.Store.ID != nil {
		emitLine("STATUS error " + b64("pair: already paired"))
		return
	}
	code, err := client.PairPhone(context.Background(), phone, true, whatsmeow.PairClientChrome, "Chrome (Linux)")
	if err != nil {
		emitLine("STATUS error " + b64("pair: "+err.Error()))
		return
	}
	emitLine("STATUS pair_ready")
	emitLine("PAIRCODE " + code)
}

func handleSend(client *whatsmeow.Client, args string) {
	parts := strings.SplitN(args, " ", 2)
	if len(parts) != 2 {
		emitLine("LOG " + b64("send: malformed"))
		return
	}
	jidStr, b64text := parts[0], parts[1]
	jid, err := types.ParseJID(jidStr)
	if err != nil {
		emitLine("LOG " + b64("send: bad jid "+jidStr))
		return
	}
	text := decodeB64(b64text)
	if text == "" {
		emitLine("LOG " + b64("send: empty text"))
		return
	}
	msg := &waE2E.Message{Conversation: ptr(text)}
	_, err = client.SendMessage(context.Background(), jid, msg)
	if err != nil {
		emitLine("LOG " + b64("send: "+err.Error()))
		return
	}
	emitLine("LOG " + b64("send: ok → "+jid.String()))
}

func handleIncoming(evt *events.Message) {
	// Skip self-sent and group system events for now.
	if evt.Info.IsFromMe {
		return
	}
	text := ""
	if evt.Message != nil {
		if evt.Message.GetConversation() != "" {
			text = evt.Message.GetConversation()
		} else if evt.Message.ExtendedTextMessage != nil && evt.Message.ExtendedTextMessage.Text != nil {
			text = *evt.Message.ExtendedTextMessage.Text
		}
	}
	if text == "" {
		return // non-text message — ignored for v1
	}
	emitLine(fmt.Sprintf("MSG %s %s %s",
		evt.Info.Sender.String(),
		b64(evt.Info.PushName),
		b64(text),
	))
}

func ptr[T any](v T) *T { return &v }
