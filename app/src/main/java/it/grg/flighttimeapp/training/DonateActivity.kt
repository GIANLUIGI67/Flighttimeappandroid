package it.grg.flighttimeapp.training

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import it.grg.flighttimeapp.R

class DonateActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_donate)
        findViewById<View>(R.id.donateBack).setOnClickListener { finish() }
    }
}
