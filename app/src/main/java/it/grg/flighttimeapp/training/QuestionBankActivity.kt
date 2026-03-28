package it.grg.flighttimeapp.training

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.card.MaterialCardView
import it.grg.flighttimeapp.R

class QuestionBankActivity : AppCompatActivity() {

    private lateinit var store: QuestionBankStore
    private var quiz: List<QuestionBankStore.QBQuestion> = emptyList()
    private var index: Int = 0
    private var scoreCorrect: Int = 0
    private var scoreTotal: Int = 0
    private var selectedLetter: String? = null
    private var locked: Boolean = false
    private var showResults: Boolean = false

    private lateinit var questionText: TextView
    private lateinit var optionsContainer: LinearLayout
    private lateinit var progressText: TextView
    private lateinit var scoreText: TextView
    private lateinit var resultContainer: View
    private lateinit var resultIcon: ImageView
    private lateinit var resultTitle: TextView
    private lateinit var resultDetail: TextView
    private lateinit var resultScore: TextView
    private lateinit var nextBtn: Button
    private lateinit var bottomBar: View
    private lateinit var scrollView: View
    private lateinit var titleText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_question_bank)

        val dbFileName = intent.getStringExtra(EXTRA_DB_FILE) ?: DEFAULT_DB_FILE
        val titleOverride = intent.getStringExtra(EXTRA_TITLE)
        store = QuestionBankStore(this, dbFileName)

        findViewById<View>(R.id.qbBack).setOnClickListener { finish() }
        findViewById<View>(R.id.qbSearch).setOnClickListener { openSearchDialog() }

        titleText = findViewById(R.id.qbTitle)
        if (!titleOverride.isNullOrBlank()) {
            titleText.text = titleOverride
        }
        questionText = findViewById(R.id.qbQuestion)
        optionsContainer = findViewById(R.id.qbOptionsContainer)
        progressText = findViewById(R.id.qbProgress)
        scoreText = findViewById(R.id.qbScore)
        resultContainer = findViewById(R.id.qbResultContainer)
        resultIcon = findViewById(R.id.qbResultIcon)
        resultTitle = findViewById(R.id.qbResultTitle)
        resultDetail = findViewById(R.id.qbResultDetail)
        resultScore = findViewById(R.id.qbResultScore)
        nextBtn = findViewById(R.id.qbNext)
        bottomBar = findViewById(R.id.qbBottomBar)
        scrollView = findViewById(R.id.qbScroll)

        findViewById<Button>(R.id.qbStartNew).setOnClickListener { startNewQuiz() }
        nextBtn.setOnClickListener { onNextPressed() }

        applySystemInsets()
        startNewQuiz()
    }

    private fun applySystemInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(bottomBar) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                v.paddingLeft,
                v.paddingTop,
                v.paddingRight,
                systemBars.bottom + resources.getDimensionPixelSize(R.dimen.qb_bottom_bar_padding)
            )
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(scrollView) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val extraBottom = resources.getDimensionPixelSize(R.dimen.qb_scroll_extra_bottom)
            v.setPadding(
                v.paddingLeft,
                v.paddingTop,
                v.paddingRight,
                systemBars.bottom + extraBottom
            )
            insets
        }
    }

    private fun currentQuestion(): QuestionBankStore.QBQuestion? {
        return if (index in quiz.indices) quiz[index] else null
    }

    private fun startNewQuiz() {
        quiz = store.newQuiz(30)
        index = 0
        scoreCorrect = 0
        scoreTotal = 0
        selectedLetter = null
        locked = false
        showResults = false
        render()
    }

    private fun loadSingleQuestion(q: QuestionBankStore.QBQuestion) {
        quiz = listOf(q)
        index = 0
        scoreCorrect = 0
        scoreTotal = 0
        selectedLetter = null
        locked = false
        showResults = false
        render()
    }

    private fun onNextPressed() {
        if (showResults) {
            finish()
            return
        }
        if (!locked) return
        selectedLetter = null
        locked = false
        if (index + 1 < quiz.size) {
            index += 1
        } else {
            showResults = true
        }
        render()
    }

    private fun select(letter: String) {
        val q = currentQuestion() ?: return
        if (locked) return
        selectedLetter = letter
        locked = true
        scoreTotal += 1
        if (letter.uppercase() == q.correct.uppercase()) {
            scoreCorrect += 1
        }
        render()
    }

    private fun render() {
        val q = currentQuestion()
        val cur = if (quiz.isEmpty()) 0 else (index + 1).coerceAtMost(quiz.size)
        progressText.text = "$cur/${maxOf(quiz.size, 1)}"
        scoreText.text = getString(R.string.training_score_format, scoreCorrect, scoreTotal)

        if (showResults) {
            showResultsUI()
            return
        }

        if (q == null) {
            questionText.text = getString(R.string.training_tap_start_quiz)
            optionsContainer.removeAllViews()
            resultContainer.visibility = View.GONE
            nextBtn.text = getString(R.string.training_next)
            nextBtn.isEnabled = false
            return
        }

        resultContainer.visibility = View.GONE
        questionText.text = q.question
        renderOptions(q)
        nextBtn.text = getString(R.string.training_next)
        nextBtn.isEnabled = locked
        applyNextButtonStyle(showResults = false, enabled = locked)
    }

    private fun showResultsUI() {
        val pct = if (scoreTotal > 0) (scoreCorrect * 100 / scoreTotal) else 0
        val passed = scoreCorrect >= 24
        val msg = getString(R.string.training_score_need_format, pct, scoreCorrect, scoreTotal, 24)
        resultDetail.text = msg
        resultScore.text = getString(R.string.training_score_format, scoreCorrect, scoreTotal)
        resultTitle.text = if (passed) "PASS" else "FAIL"
        resultTitle.setTextColor(getColor(if (passed) R.color.iosGreen else R.color.iosRed))
        val iconRes = if (passed) R.drawable.ic_check_circle else R.drawable.ic_fail_circle
        resultIcon.setImageResource(iconRes)
        resultIcon.setColorFilter(getColor(if (passed) R.color.iosGreen else R.color.iosRed))

        resultContainer.visibility = View.VISIBLE
        optionsContainer.removeAllViews()
        questionText.text = ""
        nextBtn.text = getString(R.string.training_back_to_menu)
        nextBtn.isEnabled = true
        applyNextButtonStyle(showResults = true, enabled = true)
    }

    private fun renderOptions(q: QuestionBankStore.QBQuestion) {
        optionsContainer.removeAllViews()
        val options = listOf(
            "A" to q.optionA,
            "B" to q.optionB,
            "C" to q.optionC,
            "D" to q.optionD
        ).mapNotNull { (letter, text) ->
            val t = text.trim()
            if (t.isEmpty()) null else letter to t
        }

        val inflater = LayoutInflater.from(this)
        for ((letter, text) in options) {
            val view = inflater.inflate(R.layout.item_qb_option, optionsContainer, false)
            val card = view.findViewById<MaterialCardView>(R.id.qbOptionCard)
            val letterView = view.findViewById<TextView>(R.id.qbOptionLetter)
            val textView = view.findViewById<TextView>(R.id.qbOptionText)
            letterView.text = "$letter."
            textView.text = text

            val state = optionState(letter, q.correct)
            applyOptionStyle(card, state)

            view.setOnClickListener {
                if (!locked) select(letter)
            }

            optionsContainer.addView(view)
        }
    }

    private fun optionState(letter: String, correct: String): OptionState {
        if (!locked) {
            return if (selectedLetter == letter) OptionState.SELECTED else OptionState.NORMAL
        }
        val isCorrect = letter.uppercase() == correct.uppercase()
        val isSelected = selectedLetter?.uppercase() == letter.uppercase()
        return when {
            isCorrect -> OptionState.CORRECT
            isSelected && !isCorrect -> OptionState.WRONG
            else -> OptionState.NORMAL
        }
    }

    private fun applyOptionStyle(card: MaterialCardView, state: OptionState) {
        when (state) {
            OptionState.NORMAL -> {
                card.setCardBackgroundColor(getColor(R.color.qbOptionNormalBg))
                card.strokeColor = getColor(R.color.qbOptionNormalStroke)
            }
            OptionState.SELECTED -> {
                card.setCardBackgroundColor(getColor(R.color.qbOptionSelectedBg))
                card.strokeColor = getColor(R.color.qbOptionSelectedStroke)
            }
            OptionState.CORRECT -> {
                card.setCardBackgroundColor(getColor(R.color.qbOptionCorrectBg))
                card.strokeColor = getColor(R.color.qbOptionCorrectStroke)
            }
            OptionState.WRONG -> {
                card.setCardBackgroundColor(getColor(R.color.qbOptionWrongBg))
                card.strokeColor = getColor(R.color.qbOptionWrongStroke)
            }
        }
    }

    private fun openSearchDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_qb_search, null, false)
        val input = dialogView.findViewById<EditText>(R.id.qbSearchInput)
        val list = dialogView.findViewById<ListView>(R.id.qbSearchList)

        val adapter = SearchAdapter(this, mutableListOf())
        list.adapter = adapter

        var results: List<QuestionBankStore.QBQuestion> = emptyList()

        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {}
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString() ?: ""
                results = store.searchQuestions(query)
                adapter.setItems(results)
            }
        })

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialogView.findViewById<View>(R.id.qbSearchClose).setOnClickListener {
            dialog.dismiss()
        }

        list.setOnItemClickListener { _, _, position, _ ->
            if (position in results.indices) {
                loadSingleQuestion(results[position])
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun applyNextButtonStyle(showResults: Boolean, enabled: Boolean) {
        if (showResults) {
            nextBtn.setBackgroundResource(R.drawable.bg_training_btn_grey_16)
            nextBtn.setTextColor(getColor(R.color.iosBlue))
        } else {
            nextBtn.setBackgroundResource(R.drawable.bg_training_btn_blue_16)
            nextBtn.setTextColor(getColor(android.R.color.white))
        }
        nextBtn.alpha = if (enabled) 1.0f else 0.45f
    }

    private enum class OptionState {
        NORMAL, SELECTED, CORRECT, WRONG
    }

    private class SearchAdapter(
        private val context: AppCompatActivity,
        private val items: MutableList<QuestionBankStore.QBQuestion>
    ) : android.widget.BaseAdapter() {

        fun setItems(newItems: List<QuestionBankStore.QBQuestion>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): QuestionBankStore.QBQuestion = items[position]
        override fun getItemId(position: Int): Long = items[position].id.toLong()

        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
            val view = convertView ?: android.view.LayoutInflater.from(context)
                .inflate(R.layout.item_qb_search_result, parent, false)
            val q = items[position]
            view.findViewById<TextView>(R.id.qbSearchQuestion).text = q.question
            view.findViewById<TextView>(R.id.qbSearchCorrect).text =
                context.getString(R.string.training_correct_format, q.correct)
            return view
        }
    }

    companion object {
        const val EXTRA_DB_FILE = "extra_db_file"
        const val EXTRA_TITLE = "extra_title"
        private const val DEFAULT_DB_FILE = "qb_a320.sqlite"
    }
}
