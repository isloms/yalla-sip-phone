package uz.yalla.sipphone.util

object PhoneNumberMasker {
    fun mask(number: String): String = when {
        number.length <= 1 -> "*".repeat(number.length)
        number.length == 2 -> "*${number.last()}"
        else -> "*".repeat(number.length - 2) + number.takeLast(2)
    }
}
