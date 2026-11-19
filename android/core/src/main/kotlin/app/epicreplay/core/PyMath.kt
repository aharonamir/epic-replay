package app.epicreplay.core

import java.math.BigDecimal
import java.math.RoundingMode

/** Python's number rules where the stage code depends on them. */
object PyMath {
    /** `round(x, digits)`: half to even on the exact binary value of x. */
    fun round(x: Double, digits: Int): Double = BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).toDouble()
}
