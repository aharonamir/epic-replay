package app.epicreplay.core

import java.math.BigDecimal
import java.math.RoundingMode

/** Python's number rules where the stage code depends on them. */
object PyMath {
    /** `round(x, digits)`: half to even on the exact binary value of x. */
    fun round(x: Double, digits: Int): Double = BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).toDouble()

    /** `f"{x:.1f}"`, rounded the same way. */
    fun fixed1(x: Double): String = BigDecimal(x).setScale(1, RoundingMode.HALF_EVEN).toPlainString()

    /** `f"{x:.2f}"`: Python rounds the exact binary value half to even, Java's own formatting rounds half up. */
    fun fixed2(x: Double): String = BigDecimal(x).setScale(2, RoundingMode.HALF_EVEN).toPlainString()

    /** `f"{x:.{places}f}"`, the same way. */
    fun fixed(x: Double, places: Int): String = BigDecimal(x).setScale(places, RoundingMode.HALF_EVEN).toPlainString()
}
