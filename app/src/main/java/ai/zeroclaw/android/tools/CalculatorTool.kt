package ai.zeroclaw.android.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * CalculatorTool — evaluate math expressions and unit conversions.
 *
 * Inspired by OpenClaw's calculator skill.
 * Pure local evaluation — no API, no internet needed.
 * Actions: eval (default), convert.
 */
class CalculatorTool : Tool {

    override val name = "calculator"

    override val description = "Evaluate math expressions and perform unit conversions. " +
            "Actions: 'eval' (calculate expression like '2+2', 'sqrt(144)', 'sin(45)'), " +
            "'convert' (unit conversion like '100 kg to lb', '5 miles to km'). " +
            "No internet needed — runs entirely on device."

    override val parameters = listOf(
        ToolParam("action", "string", "One of: eval, convert. Default: eval.", required = false),
        ToolParam("expression", "string", "Math expression to evaluate (for 'eval')", required = false),
        ToolParam("value", "string", "Numeric value to convert (for 'convert')", required = false),
        ToolParam("from", "string", "Source unit (for 'convert', e.g. 'kg', 'miles', 'celsius')", required = false),
        ToolParam("to", "string", "Target unit (for 'convert', e.g. 'lb', 'km', 'fahrenheit')", required = false)
    )

    override suspend fun execute(args: Map<String, String>): ToolResult {
        val action = args["action"]?.trim()?.lowercase() ?: "eval"

        return withContext(Dispatchers.Default) {
            try {
                when (action) {
                    "eval" -> {
                        val expr = args["expression"]?.trim()
                            ?: return@withContext ToolResult(false, "", "Missing 'expression' parameter")
                        evaluateExpression(expr)
                    }
                    "convert" -> {
                        val value = args["value"]?.trim()?.toDoubleOrNull()
                            ?: return@withContext ToolResult(false, "", "Missing or invalid 'value' parameter")
                        val from = args["from"]?.trim()?.lowercase()
                            ?: return@withContext ToolResult(false, "", "Missing 'from' unit")
                        val to = args["to"]?.trim()?.lowercase()
                            ?: return@withContext ToolResult(false, "", "Missing 'to' unit")
                        convertUnits(value, from, to)
                    }
                    else -> ToolResult(false, "", "Unknown action: $action. Use: eval, convert.")
                }
            } catch (e: Exception) {
                ToolResult(false, "", "Calculator error: ${e.message}")
            }
        }
    }

    // ── Expression Evaluator ─────────────────────────────────────────────

    private fun evaluateExpression(expr: String): ToolResult {
        if (expr.isBlank()) return ToolResult(false, "", "Expression cannot be empty")

        // Sanitize: only allow math characters
        val sanitized = expr.replace(" ", "")
        if (sanitized.isEmpty()) return ToolResult(false, "", "Empty expression")

        return try {
            val result = parseExpression(preprocessExpression(expr))
            val formatted = if (result == result.toLong().toDouble()) {
                result.toLong().toString()
            } else {
                "%.10g".format(result)
            }
            ToolResult(true, "$expr = $formatted")
        } catch (e: Exception) {
            ToolResult(false, "", "Cannot evaluate \"$expr\": ${e.message}")
        }
    }

    private fun preprocessExpression(expr: String): String {
        var s = expr.trim()
        // Replace common symbols and functions
        s = s.replace("×", "*").replace("÷", "/").replace("^", "**")
        s = s.replace("π", "${Math.PI}").replace("pi", "${Math.PI}", ignoreCase = true)
        s = s.replace("\\be\\b".toRegex(), "${Math.E}")
        return s
    }

    /**
     * Recursive descent parser for math expressions.
     * Supports: +, -, *, /, **, %, parentheses, and functions (sqrt, sin, cos, tan, log, ln, abs, ceil, floor, round).
     */
    private fun parseExpression(expr: String): Double {
        val parser = ExprParser(expr)
        val result = parser.parseExpr()
        if (parser.pos < parser.tokens.length) {
            throw IllegalArgumentException("Unexpected character at position ${parser.pos}: '${parser.tokens[parser.pos]}'")
        }
        return result
    }

    private class ExprParser(input: String) {
        val tokens = input.replace(" ", "")
        var pos = 0

        fun parseExpr(): Double {
            var result = parseTerm()
            while (pos < tokens.length) {
                when (tokens[pos]) {
                    '+' -> { pos++; result += parseTerm() }
                    '-' -> { pos++; result -= parseTerm() }
                    else -> break
                }
            }
            return result
        }

        private fun parseTerm(): Double {
            var result = parsePower()
            while (pos < tokens.length) {
                when (tokens[pos]) {
                    '*' -> {
                        pos++
                        if (pos < tokens.length && tokens[pos] == '*') {
                            pos++ // ** power
                            result = Math.pow(result, parsePower())
                        } else {
                            result *= parsePower()
                        }
                    }
                    '/' -> { pos++; result /= parsePower() }
                    '%' -> { pos++; result %= parsePower() }
                    else -> break
                }
            }
            return result
        }

        private fun parsePower(): Double {
            var result = parseUnary()
            if (pos < tokens.length - 1 && tokens[pos] == '*' && tokens[pos + 1] == '*') {
                pos += 2
                result = Math.pow(result, parseUnary())
            }
            return result
        }

        private fun parseUnary(): Double {
            if (pos < tokens.length && tokens[pos] == '-') {
                pos++
                return -parseAtom()
            }
            if (pos < tokens.length && tokens[pos] == '+') {
                pos++
            }
            return parseAtom()
        }

        private fun parseAtom(): Double {
            // Parentheses
            if (pos < tokens.length && tokens[pos] == '(') {
                pos++ // skip '('
                val result = parseExpr()
                if (pos < tokens.length && tokens[pos] == ')') pos++ // skip ')'
                return applyPostfix(result)
            }

            // Functions
            val funcNames = listOf("sqrt", "sin", "cos", "tan", "asin", "acos", "atan",
                "log10", "log2", "log", "ln", "abs", "ceil", "floor", "round", "exp")
            for (fn in funcNames) {
                if (tokens.startsWith(fn, pos, ignoreCase = true)) {
                    pos += fn.length
                    if (pos < tokens.length && tokens[pos] == '(') {
                        pos++
                        val arg = parseExpr()
                        if (pos < tokens.length && tokens[pos] == ')') pos++
                        return applyPostfix(applyFunction(fn.lowercase(), arg))
                    }
                }
            }

            // Number
            val start = pos
            if (pos < tokens.length && (tokens[pos].isDigit() || tokens[pos] == '.')) {
                while (pos < tokens.length && (tokens[pos].isDigit() || tokens[pos] == '.')) pos++
                // Handle scientific notation
                if (pos < tokens.length && (tokens[pos] == 'e' || tokens[pos] == 'E')) {
                    pos++
                    if (pos < tokens.length && (tokens[pos] == '+' || tokens[pos] == '-')) pos++
                    while (pos < tokens.length && tokens[pos].isDigit()) pos++
                }
                return applyPostfix(tokens.substring(start, pos).toDouble())
            }

            throw IllegalArgumentException("Unexpected token at position $pos")
        }

        private fun applyPostfix(value: Double): Double {
            // Handle factorial
            if (pos < tokens.length && tokens[pos] == '!') {
                pos++
                return factorial(value.toLong()).toDouble()
            }
            return value
        }

        private fun applyFunction(name: String, arg: Double): Double {
            return when (name) {
                "sqrt" -> Math.sqrt(arg)
                "sin" -> Math.sin(Math.toRadians(arg))
                "cos" -> Math.cos(Math.toRadians(arg))
                "tan" -> Math.tan(Math.toRadians(arg))
                "asin" -> Math.toDegrees(Math.asin(arg))
                "acos" -> Math.toDegrees(Math.acos(arg))
                "atan" -> Math.toDegrees(Math.atan(arg))
                "log", "log10" -> Math.log10(arg)
                "log2" -> Math.log(arg) / Math.log(2.0)
                "ln" -> Math.log(arg)
                "abs" -> Math.abs(arg)
                "ceil" -> Math.ceil(arg)
                "floor" -> Math.floor(arg)
                "round" -> Math.round(arg).toDouble()
                "exp" -> Math.exp(arg)
                else -> throw IllegalArgumentException("Unknown function: $name")
            }
        }

        private fun factorial(n: Long): Long {
            if (n < 0) throw IllegalArgumentException("Factorial of negative number")
            if (n > 20) throw IllegalArgumentException("Factorial too large (max 20!)")
            var result = 1L
            for (i in 2..n) result *= i
            return result
        }
    }

    // ── Unit Converter ───────────────────────────────────────────────────

    private fun convertUnits(value: Double, from: String, to: String): ToolResult {
        val fromUnit = normalizeUnit(from)
        val toUnit = normalizeUnit(to)

        if (fromUnit == toUnit) {
            return ToolResult(true, "$value $from = $value $to (same unit)")
        }

        // Temperature (special case — not multiplicative)
        val tempResult = convertTemperature(value, fromUnit, toUnit)
        if (tempResult != null) {
            return ToolResult(true, "$value $from = ${"%.4g".format(tempResult)} $to")
        }

        // Find conversion via base units
        val fromBase = unitToBase[fromUnit]
        val toBase = unitToBase[toUnit]

        if (fromBase == null) return ToolResult(false, "", "Unknown unit: '$from'. Supported: ${getSupportedUnits()}")
        if (toBase == null) return ToolResult(false, "", "Unknown unit: '$to'. Supported: ${getSupportedUnits()}")

        // Check compatible categories
        val fromCat = unitCategory[fromUnit]
        val toCat = unitCategory[toUnit]
        if (fromCat != toCat) {
            return ToolResult(false, "", "Cannot convert $from ($fromCat) to $to ($toCat) — incompatible unit types.")
        }

        val baseValue = value * fromBase
        val result = baseValue / toBase

        val formatted = if (result == result.toLong().toDouble()) {
            result.toLong().toString()
        } else {
            "%.6g".format(result)
        }

        return ToolResult(true, "$value $from = $formatted $to")
    }

    private fun convertTemperature(value: Double, from: String, to: String): Double? {
        return when {
            from == "celsius" && to == "fahrenheit" -> value * 9.0 / 5.0 + 32
            from == "fahrenheit" && to == "celsius" -> (value - 32) * 5.0 / 9.0
            from == "celsius" && to == "kelvin" -> value + 273.15
            from == "kelvin" && to == "celsius" -> value - 273.15
            from == "fahrenheit" && to == "kelvin" -> (value - 32) * 5.0 / 9.0 + 273.15
            from == "kelvin" && to == "fahrenheit" -> (value - 273.15) * 9.0 / 5.0 + 32
            else -> null
        }
    }

    private fun normalizeUnit(unit: String): String {
        return unitAliases[unit.lowercase()] ?: unit.lowercase()
    }

    private fun getSupportedUnits(): String {
        return "Length (m, km, mi, ft, in, yd, cm, mm), Weight (kg, g, lb, oz, ton, mg), " +
                "Volume (l, ml, gal, qt, pt, cup, fl_oz, tbsp, tsp), " +
                "Temperature (celsius, fahrenheit, kelvin), " +
                "Speed (m/s, km/h, mph, knot), Time (s, min, h, day, week, month, year), " +
                "Data (b, kb, mb, gb, tb, pb)"
    }

    companion object {
        // Unit aliases → canonical name
        private val unitAliases = mapOf(
            // Length
            "m" to "meter", "meter" to "meter", "meters" to "meter", "metre" to "meter",
            "km" to "kilometer", "kilometer" to "kilometer", "kilometers" to "kilometer",
            "mi" to "mile", "mile" to "mile", "miles" to "mile",
            "ft" to "foot", "foot" to "foot", "feet" to "foot",
            "in" to "inch", "inch" to "inch", "inches" to "inch",
            "yd" to "yard", "yard" to "yard", "yards" to "yard",
            "cm" to "centimeter", "centimeter" to "centimeter", "centimeters" to "centimeter",
            "mm" to "millimeter", "millimeter" to "millimeter", "millimeters" to "millimeter",
            "nm" to "nautical_mile", "nautical mile" to "nautical_mile", "nmi" to "nautical_mile",
            // Weight
            "kg" to "kilogram", "kilogram" to "kilogram", "kilograms" to "kilogram",
            "g" to "gram", "gram" to "gram", "grams" to "gram",
            "mg" to "milligram", "milligram" to "milligram", "milligrams" to "milligram",
            "lb" to "pound", "lbs" to "pound", "pound" to "pound", "pounds" to "pound",
            "oz" to "ounce", "ounce" to "ounce", "ounces" to "ounce",
            "ton" to "ton", "tons" to "ton", "tonne" to "metric_ton", "tonnes" to "metric_ton",
            "st" to "stone", "stone" to "stone", "stones" to "stone",
            // Volume
            "l" to "liter", "liter" to "liter", "liters" to "liter", "litre" to "liter",
            "ml" to "milliliter", "milliliter" to "milliliter", "milliliters" to "milliliter",
            "gal" to "gallon", "gallon" to "gallon", "gallons" to "gallon",
            "qt" to "quart", "quart" to "quart", "quarts" to "quart",
            "pt" to "pint", "pint" to "pint", "pints" to "pint",
            "cup" to "cup", "cups" to "cup",
            "fl_oz" to "fluid_ounce", "floz" to "fluid_ounce", "fluid ounce" to "fluid_ounce",
            "tbsp" to "tablespoon", "tablespoon" to "tablespoon", "tablespoons" to "tablespoon",
            "tsp" to "teaspoon", "teaspoon" to "teaspoon", "teaspoons" to "teaspoon",
            // Temperature
            "c" to "celsius", "celsius" to "celsius", "°c" to "celsius",
            "f" to "fahrenheit", "fahrenheit" to "fahrenheit", "°f" to "fahrenheit",
            "k" to "kelvin", "kelvin" to "kelvin",
            // Speed
            "m/s" to "mps", "mps" to "mps",
            "km/h" to "kmh", "kmh" to "kmh", "kph" to "kmh",
            "mph" to "mph",
            "knot" to "knot", "knots" to "knot", "kn" to "knot",
            // Time
            "s" to "second", "sec" to "second", "second" to "second", "seconds" to "second",
            "min" to "minute", "minute" to "minute", "minutes" to "minute",
            "h" to "hour", "hr" to "hour", "hour" to "hour", "hours" to "hour",
            "day" to "day", "days" to "day",
            "week" to "week", "weeks" to "week",
            "month" to "month", "months" to "month",
            "year" to "year", "years" to "year", "yr" to "year",
            // Data
            "b" to "byte", "byte" to "byte", "bytes" to "byte",
            "kb" to "kilobyte", "kilobyte" to "kilobyte", "kilobytes" to "kilobyte",
            "mb" to "megabyte", "megabyte" to "megabyte", "megabytes" to "megabyte",
            "gb" to "gigabyte", "gigabyte" to "gigabyte", "gigabytes" to "gigabyte",
            "tb" to "terabyte", "terabyte" to "terabyte", "terabytes" to "terabyte",
            "pb" to "petabyte", "petabyte" to "petabyte", "petabytes" to "petabyte"
        )

        // Unit → base unit factor (base: meter, gram, liter, second, mps, byte)
        private val unitToBase = mapOf(
            // Length (base: meter)
            "meter" to 1.0, "kilometer" to 1000.0, "mile" to 1609.344,
            "foot" to 0.3048, "inch" to 0.0254, "yard" to 0.9144,
            "centimeter" to 0.01, "millimeter" to 0.001, "nautical_mile" to 1852.0,
            // Weight (base: gram)
            "kilogram" to 1000.0, "gram" to 1.0, "milligram" to 0.001,
            "pound" to 453.592, "ounce" to 28.3495, "ton" to 907184.74,
            "metric_ton" to 1_000_000.0, "stone" to 6350.29,
            // Volume (base: liter)
            "liter" to 1.0, "milliliter" to 0.001, "gallon" to 3.78541,
            "quart" to 0.946353, "pint" to 0.473176, "cup" to 0.236588,
            "fluid_ounce" to 0.0295735, "tablespoon" to 0.0147868, "teaspoon" to 0.00492892,
            // Speed (base: m/s)
            "mps" to 1.0, "kmh" to 0.277778, "mph" to 0.44704, "knot" to 0.514444,
            // Time (base: second)
            "second" to 1.0, "minute" to 60.0, "hour" to 3600.0,
            "day" to 86400.0, "week" to 604800.0, "month" to 2_592_000.0, "year" to 31_536_000.0,
            // Data (base: byte)
            "byte" to 1.0, "kilobyte" to 1024.0, "megabyte" to 1_048_576.0,
            "gigabyte" to 1_073_741_824.0, "terabyte" to 1_099_511_627_776.0,
            "petabyte" to 1_125_899_906_842_624.0
        )

        // Unit → category (for compatibility check)
        private val unitCategory = mapOf(
            "meter" to "length", "kilometer" to "length", "mile" to "length",
            "foot" to "length", "inch" to "length", "yard" to "length",
            "centimeter" to "length", "millimeter" to "length", "nautical_mile" to "length",
            "kilogram" to "weight", "gram" to "weight", "milligram" to "weight",
            "pound" to "weight", "ounce" to "weight", "ton" to "weight",
            "metric_ton" to "weight", "stone" to "weight",
            "liter" to "volume", "milliliter" to "volume", "gallon" to "volume",
            "quart" to "volume", "pint" to "volume", "cup" to "volume",
            "fluid_ounce" to "volume", "tablespoon" to "volume", "teaspoon" to "volume",
            "mps" to "speed", "kmh" to "speed", "mph" to "speed", "knot" to "speed",
            "second" to "time", "minute" to "time", "hour" to "time",
            "day" to "time", "week" to "time", "month" to "time", "year" to "time",
            "byte" to "data", "kilobyte" to "data", "megabyte" to "data",
            "gigabyte" to "data", "terabyte" to "data", "petabyte" to "data"
        )
    }
}
