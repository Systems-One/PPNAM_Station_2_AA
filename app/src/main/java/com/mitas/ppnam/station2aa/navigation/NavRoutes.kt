package com.mitas.ppnam.station2aa.navigation

object NavRoutes {
    const val HOME = "home"
    const val LOGIN = "login"
    const val SETTINGS = "settings"
    const val MIXING = "mixing"
    const val JOB_LOOKUP = "mixing/job_lookup"
    const val INGREDIENT_SCAN = "mixing/ingredient_scan/{orderNo}"
    const val RFID_RECOVERY = "rfid/recovery"
    const val MIXING_BOARD = "mixing_board"
    const val MIXING_AREAS = "mixing_board/areas?pendingCollectionId={pendingCollectionId}"
    const val MIXING_AREA_BOARD = "mixing_board/area/{area}"

    fun ingredientScan(orderNo: String) = "mixing/ingredient_scan/$orderNo"

    fun mixingAreas(pendingCollectionId: String? = null) =
        if (pendingCollectionId.isNullOrBlank()) "mixing_board/areas"
        else "mixing_board/areas?pendingCollectionId=$pendingCollectionId"

    fun mixingAreaBoard(areaWire: String) = "mixing_board/area/$areaWire"
}
