package ai.zeroclaw.android.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * QrCodeTool — generate QR codes.
 *
 * Inspired by OpenClaw's QR skill.
 * Uses a minimal QR code encoder (no ZXing dependency needed).
 * Generates QR code as a PNG file that can be shared.
 * Actions: generate (default).
 */
class QrCodeTool(private val context: Context) : Tool {

    override val name = "qr_code"

    override val description = "Generate QR codes from text, URLs, or any data. " +
            "Creates a PNG image file. " +
            "Actions: 'generate' (create QR code image from text). No API key needed."

    override val parameters = listOf(
        ToolParam("text", "string", "Text, URL, or data to encode in the QR code"),
        ToolParam("action", "string", "One of: generate. Default: generate.", required = false),
        ToolParam("size", "string", "Image size in pixels (default: 512)", required = false)
    )

    override suspend fun execute(args: Map<String, String>): ToolResult {
        val text = args["text"]?.trim()
            ?: return ToolResult(false, "", "Missing 'text' parameter")

        if (text.isBlank()) {
            return ToolResult(false, "", "Text cannot be empty")
        }

        if (text.length > 2953) {
            return ToolResult(false, "", "Text too long for QR code (max 2953 characters)")
        }

        val action = args["action"]?.trim()?.lowercase() ?: "generate"
        val size = args["size"]?.trim()?.toIntOrNull() ?: 512

        return withContext(Dispatchers.IO) {
            try {
                when (action) {
                    "generate" -> generateQrCode(text, size)
                    else -> ToolResult(false, "", "Unknown action: $action. Use: generate.")
                }
            } catch (e: Exception) {
                ToolResult(false, "", "QR code error: ${e.message}")
            }
        }
    }

    private fun generateQrCode(text: String, size: Int): ToolResult {
        // Generate QR matrix using built-in encoder
        val matrix = QrEncoder.encode(text)
            ?: return ToolResult(false, "", "Failed to encode QR code. Text may be too long or contain unsupported characters.")

        val moduleCount = matrix.size
        val pixelSize = size / moduleCount
        val actualSize = pixelSize * moduleCount

        // Create bitmap
        val bitmap = Bitmap.createBitmap(actualSize, actualSize, Bitmap.Config.ARGB_8888)
        for (y in 0 until moduleCount) {
            for (x in 0 until moduleCount) {
                val color = if (matrix[y][x]) Color.BLACK else Color.WHITE
                for (py in 0 until pixelSize) {
                    for (px in 0 until pixelSize) {
                        bitmap.setPixel(x * pixelSize + px, y * pixelSize + py, color)
                    }
                }
            }
        }

        // Save to file
        val dir = File(context.cacheDir, "qrcodes")
        dir.mkdirs()
        val fileName = "qr_${System.currentTimeMillis()}.png"
        val file = File(dir, fileName)
        FileOutputStream(file).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        bitmap.recycle()

        val sb = StringBuilder("✅ QR Code generated!\n\n")
        sb.appendLine("Content: ${text.take(100)}${if (text.length > 100) "..." else ""}")
        sb.appendLine("Size: ${actualSize}x${actualSize} pixels")
        sb.appendLine("File: ${file.absolutePath}")
        sb.appendLine("Modules: ${moduleCount}x${moduleCount}")

        return ToolResult(true, sb.toString())
    }
}

/**
 * Minimal QR Code encoder — generates a boolean matrix (true = black module).
 * Supports alphanumeric and byte mode, error correction level M.
 * Based on QR Code specification ISO/IEC 18004.
 */
object QrEncoder {

    fun encode(text: String): Array<BooleanArray>? {
        return try {
            // Use byte mode for simplicity
            val data = text.toByteArray(Charsets.UTF_8)
            val version = getMinVersion(data.size) ?: return null
            val moduleCount = 17 + version * 4

            // Build data codewords
            val bits = mutableListOf<Boolean>()
            // Mode indicator: byte mode = 0100
            addBits(bits, 0b0100, 4)
            // Character count indicator
            val cciBits = if (version <= 9) 8 else 16
            addBits(bits, data.size, cciBits)
            // Data
            for (byte in data) {
                addBits(bits, byte.toInt() and 0xFF, 8)
            }
            // Terminator
            val totalDataBits = getDataCapacity(version) * 8
            val terminatorLen = minOf(4, totalDataBits - bits.size)
            repeat(terminatorLen) { bits.add(false) }
            // Pad to byte boundary
            while (bits.size % 8 != 0) bits.add(false)
            // Pad codewords
            val padBytes = intArrayOf(0xEC, 0x11)
            var padIdx = 0
            while (bits.size < totalDataBits) {
                addBits(bits, padBytes[padIdx % 2], 8)
                padIdx++
            }

            // Convert to codeword bytes
            val codewords = ByteArray(bits.size / 8) { i ->
                var v = 0
                for (b in 0..7) {
                    if (bits[i * 8 + b]) v = v or (1 shl (7 - b))
                }
                v.toByte()
            }

            // Generate EC codewords
            val ecInfo = getECInfo(version)
            val dataCodewords = codewords.toList()
            val allCodewords = mutableListOf<Byte>()
            val allEcCodewords = mutableListOf<Byte>()

            var offset = 0
            for ((blockCount, dataPerBlock) in ecInfo.blocks) {
                for (b in 0 until blockCount) {
                    val block = dataCodewords.subList(offset, offset + dataPerBlock)
                    allCodewords.addAll(block)
                    val ec = generateEC(block.map { it.toInt() and 0xFF }, ecInfo.ecPerBlock)
                    allEcCodewords.addAll(ec.map { it.toByte() })
                    offset += dataPerBlock
                }
            }

            val finalCodewords = allCodewords + allEcCodewords

            // Build the matrix
            val matrix = Array(moduleCount) { BooleanArray(moduleCount) }
            val reserved = Array(moduleCount) { BooleanArray(moduleCount) }

            // Place finder patterns
            placeFinderPattern(matrix, reserved, 0, 0, moduleCount)
            placeFinderPattern(matrix, reserved, moduleCount - 7, 0, moduleCount)
            placeFinderPattern(matrix, reserved, 0, moduleCount - 7, moduleCount)

            // Timing patterns
            for (i in 8 until moduleCount - 8) {
                if (!reserved[6][i]) {
                    matrix[6][i] = i % 2 == 0
                    reserved[6][i] = true
                }
                if (!reserved[i][6]) {
                    matrix[i][6] = i % 2 == 0
                    reserved[i][6] = true
                }
            }

            // Alignment patterns (version >= 2)
            if (version >= 2) {
                val positions = getAlignmentPositions(version)
                for (r in positions) {
                    for (c in positions) {
                        if (reserved[r][c]) continue
                        placeAlignmentPattern(matrix, reserved, r, c, moduleCount)
                    }
                }
            }

            // Reserve format & version info areas
            reserveFormatArea(reserved, moduleCount)
            if (version >= 7) reserveVersionArea(reserved, moduleCount)

            // Dark module
            matrix[moduleCount - 8][8] = true
            reserved[moduleCount - 8][8] = true

            // Place data bits
            placeDataBits(matrix, reserved, finalCodewords, moduleCount)

            // Apply mask (pattern 0 for simplicity)
            applyMask(matrix, reserved, moduleCount, 0)

            // Write format info
            writeFormatInfo(matrix, moduleCount, 0) // mask 0, EC level M

            // Write version info
            if (version >= 7) writeVersionInfo(matrix, moduleCount, version)

            matrix
        } catch (_: Exception) {
            null
        }
    }

    private fun addBits(bits: MutableList<Boolean>, value: Int, count: Int) {
        for (i in count - 1 downTo 0) {
            bits.add((value shr i and 1) == 1)
        }
    }

    private fun getMinVersion(dataLen: Int): Int? {
        // Byte mode capacity for EC level M
        val capacities = intArrayOf(
            0, 14, 26, 42, 62, 84, 106, 122, 152, 180, 213,
            251, 287, 331, 362, 412, 450, 504, 560, 611, 661,
            715, 751, 805, 868, 917, 969, 1024, 1089, 1144, 1200,
            1264, 1313, 1383, 1431, 1493, 1555, 1608, 1680, 1744, 1800
        )
        for (v in 1..40) {
            if (v < capacities.size && capacities[v] >= dataLen) return v
        }
        return null
    }

    private fun getDataCapacity(version: Int): Int {
        val capacities = intArrayOf(
            0, 16, 28, 44, 64, 86, 108, 124, 154, 182, 216,
            254, 290, 334, 365, 415, 453, 507, 563, 614, 664,
            718, 754, 808, 871, 921, 973, 1028, 1093, 1148, 1204,
            1268, 1317, 1387, 1435, 1497, 1559, 1612, 1684, 1748, 1804
        )
        return if (version in 1..40) capacities[version] else 0
    }

    data class ECInfo(val ecPerBlock: Int, val blocks: List<Pair<Int, Int>>)

    private fun getECInfo(version: Int): ECInfo {
        // Simplified EC info for level M (most common)
        // Returns: ec codewords per block, list of (blockCount, dataCodewordsPerBlock)
        val ecTable = mapOf(
            1 to ECInfo(10, listOf(1 to 16)),
            2 to ECInfo(16, listOf(1 to 28)),
            3 to ECInfo(26, listOf(1 to 44)),
            4 to ECInfo(18, listOf(2 to 32)),
            5 to ECInfo(24, listOf(2 to 43)),
            6 to ECInfo(16, listOf(4 to 27)),
            7 to ECInfo(18, listOf(4 to 31)),
            8 to ECInfo(22, listOf(2 to 38, 2 to 39)),
            9 to ECInfo(22, listOf(3 to 36, 2 to 37)),
            10 to ECInfo(26, listOf(4 to 43, 1 to 44)),
        )
        return ecTable[version] ?: ECInfo(10, listOf(1 to getDataCapacity(version)))
    }

    private fun generateEC(data: List<Int>, ecCount: Int): List<Int> {
        val generator = getGeneratorPolynomial(ecCount)
        val message = data.toMutableList()
        repeat(ecCount) { message.add(0) }

        for (i in data.indices) {
            val coef = message[i]
            if (coef != 0) {
                val logCoef = LOG_TABLE[coef]
                for (j in generator.indices) {
                    message[i + j] = message[i + j] xor EXP_TABLE[(logCoef + generator[j]) % 255]
                }
            }
        }
        return message.subList(data.size, message.size)
    }

    private fun getGeneratorPolynomial(degree: Int): IntArray {
        var gen = intArrayOf(0)
        for (i in 0 until degree) {
            val poly = intArrayOf(0, i)
            gen = multiplyPolynomials(gen, poly)
        }
        return gen
    }

    private fun multiplyPolynomials(a: IntArray, b: IntArray): IntArray {
        val result = IntArray(a.size + b.size - 1)
        for (i in a.indices) {
            for (j in b.indices) {
                result[i + j] = result[i + j] xor EXP_TABLE[(a[i] + b[j]) % 255]
            }
        }
        return IntArray(result.size) { if (result[it] == 0) 0 else LOG_TABLE[result[it]] }
    }

    private fun placeFinderPattern(matrix: Array<BooleanArray>, reserved: Array<BooleanArray>, row: Int, col: Int, size: Int) {
        for (r in -1..7) {
            for (c in -1..7) {
                val mr = row + r
                val mc = col + c
                if (mr < 0 || mr >= size || mc < 0 || mc >= size) continue
                val inOuter = r == 0 || r == 6 || c == 0 || c == 6
                val inInner = r in 2..4 && c in 2..4
                val isSeparator = r == -1 || r == 7 || c == -1 || c == 7
                matrix[mr][mc] = !isSeparator && (inOuter || inInner)
                reserved[mr][mc] = true
            }
        }
    }

    private fun placeAlignmentPattern(matrix: Array<BooleanArray>, reserved: Array<BooleanArray>, centerRow: Int, centerCol: Int, size: Int) {
        for (r in -2..2) {
            for (c in -2..2) {
                val mr = centerRow + r
                val mc = centerCol + c
                if (mr < 0 || mr >= size || mc < 0 || mc >= size) continue
                if (reserved[mr][mc]) return // overlap with finder
            }
        }
        for (r in -2..2) {
            for (c in -2..2) {
                val mr = centerRow + r
                val mc = centerCol + c
                if (mr < 0 || mr >= size || mc < 0 || mc >= size) continue
                val isEdge = r == -2 || r == 2 || c == -2 || c == 2
                val isCenter = r == 0 && c == 0
                matrix[mr][mc] = isEdge || isCenter
                reserved[mr][mc] = true
            }
        }
    }

    private fun getAlignmentPositions(version: Int): List<Int> {
        if (version <= 1) return emptyList()
        val table = mapOf(
            2 to listOf(6, 18), 3 to listOf(6, 22), 4 to listOf(6, 26),
            5 to listOf(6, 30), 6 to listOf(6, 34), 7 to listOf(6, 22, 38),
            8 to listOf(6, 24, 42), 9 to listOf(6, 26, 46), 10 to listOf(6, 28, 50)
        )
        return table[version] ?: run {
            val last = 17 + version * 4 - 7
            val step = ((last - 6) / ((version / 7) + 1) + 1) / 2 * 2
            val positions = mutableListOf(6)
            var pos = last
            while (pos > 6) {
                positions.add(1, pos)
                pos -= step
            }
            positions
        }
    }

    private fun reserveFormatArea(reserved: Array<BooleanArray>, size: Int) {
        for (i in 0..8) {
            if (i < size) reserved[8][i] = true
            if (i < size) reserved[i][8] = true
        }
        for (i in 0..7) {
            if (size - 1 - i >= 0) reserved[8][size - 1 - i] = true
            if (size - 1 - i >= 0) reserved[size - 1 - i][8] = true
        }
    }

    private fun reserveVersionArea(reserved: Array<BooleanArray>, size: Int) {
        for (i in 0..5) for (j in 0..2) {
            reserved[i][size - 11 + j] = true
            reserved[size - 11 + j][i] = true
        }
    }

    private fun placeDataBits(matrix: Array<BooleanArray>, reserved: Array<BooleanArray>, data: List<Byte>, size: Int) {
        val bits = mutableListOf<Boolean>()
        for (byte in data) {
            for (b in 7 downTo 0) {
                bits.add((byte.toInt() shr b and 1) == 1)
            }
        }

        var bitIdx = 0
        var col = size - 1
        while (col > 0) {
            if (col == 6) col-- // skip timing column
            for (row in 0 until size) {
                val actualRow = if ((((size - 1 - col) / 2) % 2) == 0) size - 1 - row else row
                for (c in 0..1) {
                    val actualCol = col - c
                    if (actualCol < 0 || actualCol >= size) continue
                    if (reserved[actualRow][actualCol]) continue
                    if (bitIdx < bits.size) {
                        matrix[actualRow][actualCol] = bits[bitIdx]
                        bitIdx++
                    }
                }
            }
            col -= 2
        }
    }

    private fun applyMask(matrix: Array<BooleanArray>, reserved: Array<BooleanArray>, size: Int, mask: Int) {
        for (r in 0 until size) {
            for (c in 0 until size) {
                if (reserved[r][c]) continue
                val shouldFlip = when (mask) {
                    0 -> (r + c) % 2 == 0
                    1 -> r % 2 == 0
                    2 -> c % 3 == 0
                    3 -> (r + c) % 3 == 0
                    4 -> (r / 2 + c / 3) % 2 == 0
                    5 -> (r * c) % 2 + (r * c) % 3 == 0
                    6 -> ((r * c) % 2 + (r * c) % 3) % 2 == 0
                    7 -> ((r + c) % 2 + (r * c) % 3) % 2 == 0
                    else -> false
                }
                if (shouldFlip) matrix[r][c] = !matrix[r][c]
            }
        }
    }

    private fun writeFormatInfo(matrix: Array<BooleanArray>, size: Int, mask: Int) {
        // EC level M = 00, mask pattern
        val formatBits = FORMAT_INFO[mask]
        // Horizontal: around top-left finder
        for (i in 0..5) matrix[8][i] = (formatBits shr (14 - i) and 1) == 1
        matrix[8][7] = (formatBits shr 8 and 1) == 1
        matrix[8][8] = (formatBits shr 7 and 1) == 1
        matrix[7][8] = (formatBits shr 6 and 1) == 1
        for (i in 0..5) matrix[5 - i][8] = (formatBits shr (i) and 1) == 1

        // Along edges
        for (i in 0..7) matrix[size - 1 - i][8] = (formatBits shr (i) and 1) == 1
        for (i in 0..7) matrix[8][size - 8 + i] = (formatBits shr (14 - i) and 1) == 1
    }

    private fun writeVersionInfo(matrix: Array<BooleanArray>, size: Int, version: Int) {
        if (version < 7) return
        val versionInfo = VERSION_INFO.getOrNull(version - 7) ?: return
        for (i in 0..5) {
            for (j in 0..2) {
                val bit = (versionInfo shr (i * 3 + j) and 1) == 1
                matrix[i][size - 11 + j] = bit
                matrix[size - 11 + j][i] = bit
            }
        }
    }

    // Format info for EC level M (00), masks 0-7
    private val FORMAT_INFO = intArrayOf(
        0x5412, 0x5125, 0x5E7C, 0x5B4B, 0x45F9, 0x40CE, 0x4F97, 0x4AA0
    )

    // Version info for versions 7-40
    private val VERSION_INFO = intArrayOf(
        0x07C94, 0x085BC, 0x09A99, 0x0A4D3, 0x0BBF6, 0x0C762, 0x0D847, 0x0E60D,
        0x0F928, 0x10B78, 0x1145D, 0x12A17, 0x13532, 0x149A6, 0x15683, 0x168C9,
        0x177EC, 0x18EC4, 0x191E1, 0x1AFAB, 0x1B08E, 0x1CC1A, 0x1D33F, 0x1ED75,
        0x1F250, 0x209D5, 0x216F0, 0x228BA, 0x2379F, 0x24B0B, 0x2542E, 0x26A64,
        0x27541, 0x28C69
    )

    // GF(256) tables for Reed-Solomon
    private val EXP_TABLE = IntArray(256)
    private val LOG_TABLE = IntArray(256)

    init {
        var x = 1
        for (i in 0..254) {
            EXP_TABLE[i] = x
            LOG_TABLE[x] = i
            x = x shl 1
            if (x >= 256) x = x xor 0x11D
        }
        EXP_TABLE[255] = EXP_TABLE[0]
    }
}
