package it.grg.flighttimeapp

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

class FlightTimeCalculatorActivity : AppCompatActivity() {

    private enum class Mode { FLIGHT_TIME, SCIENTIFIC }

    private var mode = Mode.FLIGHT_TIME
    private lateinit var flightTab: TextView
    private lateinit var scientificTab: TextView
    private lateinit var contentHost: LinearLayout

    private var timeDisplay = "0"
    private var timeExpression = "Flight time"
    private var timeAccumulatorMinutes: Double? = null
    private var timePendingOperation: String? = null
    private var timeShouldResetInput = true
    private var allowNegativeTotals = false
    private val timeTape = mutableListOf<String>()
    private lateinit var timeDisplayText: TextView
    private lateinit var timeExpressionText: TextView
    private lateinit var timeNegativeLabel: TextView
    private lateinit var timeTapeList: LinearLayout

    private var sciDisplay = "0"
    private var sciExpression = "Scientific"
    private var sciAccumulator: Double? = null
    private var sciPendingOperation: String? = null
    private var sciShouldResetInput = false
    private lateinit var sciDisplayText: TextView
    private lateinit var sciExpressionText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(color(R.color.iosBackground))
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(28))
        }
        scroll.addView(root, LinearLayout.LayoutParams(-1, -2))
        setContentView(scroll)

        root.addView(header())
        root.addView(segmentControl(), topMargin = dp(18))

        contentHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(contentHost, topMargin = dp(14))

        showMode(Mode.FLIGHT_TIME)
    }

    private fun header(): View {
        return FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(58))

            addView(ImageButton(context).apply {
                setImageResource(R.drawable.ic_back)
                setColorFilter(color(R.color.homeNavy))
                background = rounded(Color.WHITE, dp(24).toFloat())
                setOnClickListener { finish() }
                contentDescription = getString(R.string.training_back_button)
                setPadding(dp(14), dp(14), dp(14), dp(14))
            }, FrameLayout.LayoutParams(dp(50), dp(50), Gravity.START or Gravity.CENTER_VERTICAL))

            addView(TextView(context).apply {
                text = getString(R.string.flight_time_calculator)
                setTextColor(color(R.color.iosText))
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                maxLines = 1
            }, FrameLayout.LayoutParams(-1, -1))
        }
    }

    private fun segmentControl(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(0xFFECEEF3.toInt(), dp(20).toFloat())
            setPadding(dp(3), dp(3), dp(3), dp(3))

            flightTab = segmentTab(getString(R.string.flight_time_line_1)) { showMode(Mode.FLIGHT_TIME) }
            scientificTab = segmentTab(getString(R.string.scientific)) { showMode(Mode.SCIENTIFIC) }
            addView(flightTab, LinearLayout.LayoutParams(0, dp(44), 1f))
            addView(scientificTab, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
    }

    private fun segmentTab(textValue: String, action: () -> Unit): TextView {
        return TextView(this).apply {
            text = textValue
            gravity = Gravity.CENTER
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { action() }
        }
    }

    private fun showMode(newMode: Mode) {
        mode = newMode
        flightTab.background = if (mode == Mode.FLIGHT_TIME) rounded(Color.WHITE, dp(18).toFloat()) else null
        scientificTab.background = if (mode == Mode.SCIENTIFIC) rounded(Color.WHITE, dp(18).toFloat()) else null
        flightTab.setTextColor(color(if (mode == Mode.FLIGHT_TIME) R.color.homeNavy else R.color.iosText))
        scientificTab.setTextColor(color(if (mode == Mode.SCIENTIFIC) R.color.homeNavy else R.color.iosText))

        contentHost.removeAllViews()
        contentHost.addView(if (mode == Mode.FLIGHT_TIME) buildFlightTimePanel() else buildScientificPanel())
    }

    private fun buildFlightTimePanel(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(timeDisplayCard())
            addView(timeKeypadCard(), topMargin = dp(14))
            addView(timeTapeCard(), topMargin = dp(14))
        }
    }

    private fun timeDisplayCard(): View {
        return FrameLayout(this).apply {
            background = gradient()
            setPadding(dp(22), dp(20), dp(22), dp(18))

            val plane = ImageView(context).apply {
                setImageResource(R.drawable.ic_airplane)
                rotation = -22f
                alpha = 0.92f
            }
            addView(plane, FrameLayout.LayoutParams(dp(54), dp(54), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(28)
                rightMargin = dp(28)
            })

            val stack = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(stack, FrameLayout.LayoutParams(-1, -1))

            val top = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
            }
            timeExpressionText = TextView(context).apply {
                text = timeExpression
                setTextColor(0xCFFFFFFF.toInt())
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
            }
            top.addView(timeExpressionText, LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(SwitchCompat(context).apply {
                isChecked = allowNegativeTotals
                setOnCheckedChangeListener { _, checked ->
                    allowNegativeTotals = checked
                    updateTimeDisplay()
                }
            })
            stack.addView(top)

            timeDisplayText = TextView(context).apply {
                text = timeDisplay
                setTextColor(Color.WHITE)
                textSize = 58f
                typeface = Typeface.MONOSPACE
                includeFontPadding = false
                maxLines = 1
            }
            stack.addView(timeDisplayText, topMargin = dp(36))

            timeNegativeLabel = TextView(context).apply {
                setTextColor(0xCFFFFFFF.toInt())
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
            }
            stack.addView(timeNegativeLabel, topMargin = dp(8))

            updateTimeDisplay()
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(-1, dp(190))
        }
    }

    private fun timeKeypadCard(): View {
        val rows = listOf(
            listOf("C", "⌫", "±", "÷"),
            listOf("7", "8", "9", "×"),
            listOf("4", "5", "6", "−"),
            listOf("1", "2", "3", "+"),
            listOf("0", ":", ".", "=")
        )
        return keypadCard(rows) { handleTimeKey(it) }
    }

    private fun timeTapeCard(): View {
        return card().apply {
            val stack = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(16), dp(18), dp(16))
            }
            val header = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
            }
            header.addView(TextView(context).apply {
                text = getString(R.string.tape)
                setTextColor(color(R.color.homeNavy))
                textSize = 21f
                typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(TextView(context).apply {
                text = getString(R.string.clear)
                setTextColor(color(R.color.homeOrange))
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setOnClickListener {
                    timeTape.clear()
                    renderTimeTape()
                }
            })
            stack.addView(header)
            timeTapeList = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            stack.addView(timeTapeList, topMargin = dp(12))
            addView(stack)
            renderTimeTape()
        }
    }

    private fun handleTimeKey(key: String) {
        when (key) {
            in "0".."9" -> appendTimeDigit(key)
            ":" -> appendTimeColon()
            "." -> appendTimeDecimal()
            "C" -> clearTime()
            "⌫" -> backspaceTime()
            "±" -> toggleTimeSign()
            "+", "−", "×", "÷" -> setTimeOperation(key)
            "=" -> resolveTimeOperation()
        }
        updateTimeDisplay()
    }

    private fun appendTimeDigit(digit: String) {
        if (timeShouldResetInput || timeDisplay == "0" || timeDisplay == "Error") {
            timeDisplay = digit
            timeShouldResetInput = false
        } else if (timeDisplay.length < 10) {
            timeDisplay += digit
        }
    }

    private fun appendTimeColon() {
        if (timeShouldResetInput || timeDisplay == "Error") {
            timeDisplay = "0:"
            timeShouldResetInput = false
        } else if (!timeDisplay.contains(":")) {
            timeDisplay += ":"
        }
    }

    private fun appendTimeDecimal() {
        if (timeShouldResetInput || timeDisplay == "Error") {
            timeDisplay = "0."
            timeShouldResetInput = false
        } else if (!timeDisplay.contains(".") && !timeDisplay.contains(":")) {
            timeDisplay += "."
        }
    }

    private fun clearTime() {
        timeDisplay = "0"
        timeExpression = getString(R.string.flight_time_line_1)
        timeAccumulatorMinutes = null
        timePendingOperation = null
        timeShouldResetInput = true
    }

    private fun backspaceTime() {
        if (timeShouldResetInput || timeDisplay == "Error") {
            timeDisplay = "0"
            timeShouldResetInput = true
            return
        }
        timeDisplay = timeDisplay.dropLast(1)
        if (timeDisplay.isBlank() || timeDisplay == "-") {
            timeDisplay = "0"
            timeShouldResetInput = true
        }
    }

    private fun toggleTimeSign() {
        if (timeDisplay == "0" || timeDisplay == "Error") return
        timeDisplay = if (timeDisplay.startsWith("-")) timeDisplay.drop(1) else "-$timeDisplay"
    }

    private fun setTimeOperation(operation: String) {
        if (timePendingOperation != null) {
            resolveTimeOperation()
        } else {
            timeAccumulatorMinutes = parseMinutes(timeDisplay)
        }
        timePendingOperation = operation
        timeExpression = "$timeDisplay $operation"
        timeShouldResetInput = true
    }

    private fun resolveTimeOperation() {
        val operation = timePendingOperation ?: return
        val left = timeAccumulatorMinutes ?: return
        val rightText = timeDisplay
        val right = if (operation == "×" || operation == "÷") parseScalar(timeDisplay) else parseMinutes(timeDisplay)
        val result = when (operation) {
            "+" -> left + right
            "−" -> left - right
            "×" -> left * right
            "÷" -> {
                if (right == 0.0) {
                    timeDisplay = "Error"
                    timeExpression = getString(R.string.division_by_zero)
                    timeAccumulatorMinutes = null
                    timePendingOperation = null
                    timeShouldResetInput = true
                    return
                }
                left / right
            }
            else -> return
        }
        val leftText = formatMinutes(left, true)
        val resultText = formatMinutes(result, allowNegativeTotals)
        timeExpression = "$leftText $operation $rightText ="
        timeDisplay = resultText
        timeTape.add("$leftText $operation $rightText = $resultText")
        timeAccumulatorMinutes = result
        timePendingOperation = null
        timeShouldResetInput = true
        renderTimeTape()
    }

    private fun parseMinutes(value: String): Double {
        val sign = if (value.startsWith("-")) -1.0 else 1.0
        val clean = value.replace("-", "")
        if (clean.contains(":")) {
            val parts = clean.split(":")
            val hours = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val minutes = parts.getOrNull(1)?.toIntOrNull() ?: 0
            return sign * ((hours * 60) + minutes)
        }
        return sign * ((clean.toDoubleOrNull() ?: 0.0) * 60.0)
    }

    private fun parseScalar(value: String): Double {
        return value.replace(":", ".").toDoubleOrNull() ?: 0.0
    }

    private fun formatMinutes(value: Double, preserveNegative: Boolean): String {
        var total = value.roundToInt()
        if (!preserveNegative) {
            val day = 24 * 60
            while (total < 0) total += day
            total %= day
        }
        val sign = if (total < 0) "-" else ""
        val absMin = abs(total)
        return "%s%02d:%02d".format(sign, absMin / 60, absMin % 60)
    }

    private fun updateTimeDisplay() {
        if (::timeDisplayText.isInitialized) timeDisplayText.text = timeDisplay
        if (::timeExpressionText.isInitialized) timeExpressionText.text = timeExpression
        if (::timeNegativeLabel.isInitialized) {
            timeNegativeLabel.text = getString(
                if (allowNegativeTotals) R.string.negative_totals_on else R.string.negative_totals_off
            )
        }
    }

    private fun renderTimeTape() {
        if (!::timeTapeList.isInitialized) return
        timeTapeList.removeAllViews()
        if (timeTape.isEmpty()) {
            timeTapeList.addView(TextView(this).apply {
                text = getString(R.string.no_calculations_yet)
                setTextColor(color(R.color.iosHint))
                textSize = 15f
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-1, dp(44)))
        } else {
            timeTape.takeLast(5).forEach {
                timeTapeList.addView(TextView(this).apply {
                    text = it
                    setTextColor(color(R.color.homeNavy))
                    textSize = 15f
                    typeface = Typeface.MONOSPACE
                }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            }
        }
    }

    private fun buildScientificPanel(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(scientificDisplayCard())
            addView(scientificKeypadCard(), topMargin = dp(14))
        }
    }

    private fun scientificDisplayCard(): View {
        return card().apply {
            val stack = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                setPadding(dp(18), dp(16), dp(18), dp(16))
            }
            sciExpressionText = TextView(context).apply {
                text = sciExpression
                setTextColor(color(R.color.iosHint))
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.END
            }
            stack.addView(sciExpressionText, LinearLayout.LayoutParams(-1, -2))
            sciDisplayText = TextView(context).apply {
                text = sciDisplay
                setTextColor(color(R.color.homeNavy))
                textSize = 42f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.END
                maxLines = 1
            }
            stack.addView(sciDisplayText, topMargin = dp(8))
            addView(stack)
        }
    }

    private fun scientificKeypadCard(): View {
        val rows = listOf(
            listOf("sin", "cos", "tan", "⌫"),
            listOf("√", "x²", "±", "÷"),
            listOf("7", "8", "9", "×"),
            listOf("4", "5", "6", "−"),
            listOf("1", "2", "3", "+"),
            listOf("0", ".", "C", "=")
        )
        return keypadCard(rows) { handleScientificKey(it) }
    }

    private fun handleScientificKey(key: String) {
        when (key) {
            in "0".."9" -> appendSciDigit(key)
            "." -> appendSciDecimal()
            "C" -> clearScientific()
            "⌫" -> backspaceScientific()
            "±" -> toggleScientificSign()
            "sin", "cos", "tan", "√", "x²" -> applyScientificFunction(key)
            "+", "−", "×", "÷" -> setScientificOperation(key)
            "=" -> resolveScientificOperation()
        }
        updateScientificDisplay()
    }

    private fun appendSciDigit(digit: String) {
        if (sciShouldResetInput || sciDisplay == "0" || sciDisplay == "Error") {
            sciDisplay = digit
            sciShouldResetInput = false
        } else if (sciDisplay.length < 12) {
            sciDisplay += digit
        }
    }

    private fun appendSciDecimal() {
        if (sciShouldResetInput || sciDisplay == "Error") {
            sciDisplay = "0."
            sciShouldResetInput = false
        } else if (!sciDisplay.contains(".")) {
            sciDisplay += "."
        }
    }

    private fun clearScientific() {
        sciDisplay = "0"
        sciExpression = getString(R.string.scientific)
        sciAccumulator = null
        sciPendingOperation = null
        sciShouldResetInput = false
    }

    private fun backspaceScientific() {
        if (sciShouldResetInput || sciDisplay == "Error") {
            sciDisplay = "0"
            sciShouldResetInput = false
            return
        }
        sciDisplay = sciDisplay.dropLast(1)
        if (sciDisplay.isBlank() || sciDisplay == "-") sciDisplay = "0"
    }

    private fun toggleScientificSign() {
        if (sciDisplay == "0" || sciDisplay == "Error") return
        sciDisplay = if (sciDisplay.startsWith("-")) sciDisplay.drop(1) else "-$sciDisplay"
    }

    private fun applyScientificFunction(function: String) {
        val value = sciDisplay.toDoubleOrNull() ?: 0.0
        val result = when (function) {
            "sin" -> sin(value)
            "cos" -> cos(value)
            "tan" -> tan(value)
            "√" -> if (value >= 0.0) sqrt(value) else Double.NaN
            "x²" -> value * value
            else -> value
        }
        sciExpression = "$function($sciDisplay)"
        sciDisplay = formatNumber(result)
        sciShouldResetInput = true
    }

    private fun setScientificOperation(operation: String) {
        if (sciPendingOperation != null) {
            resolveScientificOperation()
        } else {
            sciAccumulator = sciDisplay.toDoubleOrNull() ?: 0.0
        }
        sciPendingOperation = operation
        sciExpression = "$sciDisplay $operation"
        sciShouldResetInput = true
    }

    private fun resolveScientificOperation() {
        val operation = sciPendingOperation ?: return
        val left = sciAccumulator ?: return
        val right = sciDisplay.toDoubleOrNull() ?: 0.0
        val result = when (operation) {
            "+" -> left + right
            "−" -> left - right
            "×" -> left * right
            "÷" -> if (right == 0.0) Double.NaN else left / right
            else -> return
        }
        sciExpression = "${formatNumber(left)} $operation ${formatNumber(right)} ="
        sciDisplay = formatNumber(result)
        sciAccumulator = null
        sciPendingOperation = null
        sciShouldResetInput = true
    }

    private fun updateScientificDisplay() {
        if (::sciDisplayText.isInitialized) sciDisplayText.text = sciDisplay
        if (::sciExpressionText.isInitialized) sciExpressionText.text = sciExpression
    }

    private fun formatNumber(value: Double): String {
        if (!value.isFinite()) return "Error"
        val rounded = value.roundToInt().toDouble()
        if (abs(value - rounded) < 0.0000001) return rounded.toInt().toString()
        return "%.8f".format(value).trimEnd('0').trimEnd('.')
    }

    private fun keypadCard(rows: List<List<String>>, onPress: (String) -> Unit): View {
        return card().apply {
            val stack = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
            rows.forEach { row ->
                val line = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                row.forEach { key ->
                    line.addView(keyButton(key, onPress), LinearLayout.LayoutParams(0, dp(58), 1f).apply {
                        leftMargin = dp(4)
                        rightMargin = dp(4)
                        topMargin = dp(4)
                        bottomMargin = dp(4)
                    })
                }
                stack.addView(line, LinearLayout.LayoutParams(-1, -2))
            }
            addView(stack)
        }
    }

    private fun keyButton(key: String, onPress: (String) -> Unit): TextView {
        val isEquals = key == "="
        val isOperator = key in setOf("+", "−", "×", "÷", "sin", "cos", "tan", "√", "x²")
        val isUtility = key in setOf("C", "⌫", "±")
        return TextView(this).apply {
            text = key
            gravity = Gravity.CENTER
            textSize = if (key.length > 1) 17f else 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(
                when {
                    isEquals -> Color.WHITE
                    isOperator -> color(R.color.homeOrange)
                    isUtility -> color(R.color.iosHint)
                    else -> color(R.color.homeNavy)
                }
            )
            background = if (isEquals) gradientButton() else rounded(0xFFF7F8FB.toInt(), dp(16).toFloat())
            setOnClickListener { onPress(key) }
        }
    }

    private fun card(): MaterialCardView {
        return MaterialCardView(this).apply {
            radius = dp(24).toFloat()
            cardElevation = dp(6).toFloat()
            setCardBackgroundColor(Color.WHITE)
            strokeWidth = dp(1)
            strokeColor = color(R.color.homeCardStroke)
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
    }

    private fun gradient(): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(color(R.color.homeNavy), 0xFF21466D.toInt(), color(R.color.homeOrange))
        ).apply {
            cornerRadius = dp(26).toFloat()
        }
    }

    private fun gradientButton(): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(color(R.color.homeOrange), color(R.color.homeGold))
        ).apply {
            cornerRadius = dp(16).toFloat()
        }
    }

    private fun rounded(colorValue: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(colorValue)
            cornerRadius = radius
        }
    }

    private fun color(res: Int): Int = ContextCompat.getColor(this, res)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun LinearLayout.addView(view: View, topMargin: Int) {
        addView(view, LinearLayout.LayoutParams(-1, -2).apply { this.topMargin = topMargin })
    }
}
