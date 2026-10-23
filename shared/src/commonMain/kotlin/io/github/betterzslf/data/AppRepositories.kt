package io.github.betterzslf.data

import io.github.betterzslf.core.net.ApiClient
import io.github.betterzslf.core.net.UploadFile
import io.github.betterzslf.core.storage.SessionStore

/** UI composition root; all modules share one ApiClient and one observable session. */
class AppRepositories(private val api: ApiClient, sessionStore: SessionStore) {
    val auth = AuthRepository(api, sessionStore)
    val profile = ProfileRepository(api)
    val content = ContentRepository(api)
    val notices = NoticeRepository(api)
    val discover = DiscoverRepository(api)
    val run = RunRepository(api)
    val venues = VenueRepository(api)
    val fitness = FitnessRepository(api)
    val campusNetwork = CampusNetworkRepository(api)
    val updates = UpdateRepository(api)

    suspend fun upload(file: UploadFile, tableId: String, childType: String = "feedbackImage", tablename: String = "sch_feedback") =
        api.upload(file, tableId, childType, tablename)

    fun fileUrl(relativePath: String): String? = api.fileUrl(relativePath)
}
