package com.mitas.ppnam.station2aa.navigation

object NavRoutes {
    const val HOME = "home"
    const val LOGIN = "login"
    const val SETTINGS = "settings"
    const val MIXING = "mixing"
    const val JOB_LOOKUP = "mixing/job_lookup"
    const val INGREDIENT_SCAN = "mixing/ingredient_scan/{orderNo}"
    const val RFID_RECOVERY = "rfid/recovery"

    fun ingredientScan(orderNo: String) = "mixing/ingredient_scan/$orderNo"
}
