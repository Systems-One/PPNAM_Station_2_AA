package com.mitas.ppnam.station2aa.navigation

object NavRoutes {
    const val HOME = "home"
    const val LOGIN = "login"
    const val SETTINGS = "settings"

    /** Parent graph for Job Lookup + Detail, which share one JobLookupViewModel. */
    const val JOBS = "jobs"
    const val JOB_LOOKUP = "jobs/lookup"
    const val JOB_DETAIL = "jobs/detail/{jobCard}"

    fun jobDetail(jobCard: String) = "jobs/detail/$jobCard"
}
