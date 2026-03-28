package it.grg.flighttimeapp.salary

import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import it.grg.flighttimeapp.R

class SalaryLockedActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_salary_locked)

        findViewById<Button>(R.id.lockedUnlockButton).setOnClickListener {
            Toast.makeText(this, getString(R.string.salary_locked_message), Toast.LENGTH_LONG).show()
        }
    }
}
