package com.mitas.ppnam.station2aa.navigation

object NavRoutes {
    const val HOME = "home"
    const val LOGIN = "login"
    const val SETTINGS = "settings"

    /** Parent graph for Job Lookup + Detail, which share one JobLookupViewModel. */
    const val JOBS = "jobs"
    const val JOB_LOOKUP = "jobs/lookup"
    const val JOB_DETAIL = "jobs/detail/{jobCard}"

    const val PREPARE = "jobs/prepare/{jobCard}"
    const val PREP = "jobs/prep/{prepId}"

    /** Rajoo (contract §5): its own job list and one job card's session screen. */
    const val RAJOO = "rajoo"
    const val RAJOO_JOB = "rajoo/job/{jobCard}"
    fun rajooJob(jobCard: String) = "rajoo/job/$jobCard"

    fun jobDetail(jobCard: String) = "jobs/detail/$jobCard"
    fun prepare(jobCard: String) = "jobs/prepare/$jobCard"
    fun prep(prepId: String) = "jobs/prep/$prepId"
}
