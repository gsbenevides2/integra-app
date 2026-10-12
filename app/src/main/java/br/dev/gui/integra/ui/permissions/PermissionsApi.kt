package br.dev.gui.integra.ui.permissions

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

// Modelos de server/modules/permissions (index.ts / model.ts).

@Immutable
@Serializable
data class UserRow(
    val id: String,
    val uid: String = "",
    val username: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
)

@Immutable
@Serializable
data class RouteGrant(
    val id: String,
    val routeKey: String,
    /** "ok" | "orphaned" */
    val status: String = "ok",
)

@Immutable
@Serializable
data class ResourceGrant(
    val id: String,
    val resourceType: String,
    val resourceId: String,
    val action: String,
    val label: String? = null,
    /** "ok" | "orphaned" | "unverified" */
    val status: String = "ok",
)

@Immutable
@Serializable
data class Grants(
    val routes: List<RouteGrant> = emptyList(),
    val resources: List<ResourceGrant> = emptyList(),
)

@Immutable
@Serializable
data class RouteOption(
    val key: String,
    val method: String = "",
    val path: String = "",
    val tag: String = "",
    val summary: String = "",
)

@Immutable
@Serializable
data class ResourceOption(
    val resourceType: String,
    val resourceId: String,
    val label: String = "",
    val actions: List<String> = emptyList(),
    val kind: String? = null,
)

@Serializable
data class ResourcesPayload(
    val resources: List<ResourceOption>? = null,
    val frigateAvailable: Boolean? = null,
)

@Serializable
data class RouteGrantBody(val routeKey: String)

@Serializable
data class ResourceGrantBody(val resourceType: String, val resourceId: String, val action: String)

interface PermissionsApi {
    @GET("api/permissions/users")
    suspend fun users(): List<UserRow>

    @GET("api/permissions/routes")
    suspend fun routes(): List<RouteOption>

    @GET("api/permissions/resources")
    suspend fun resources(): ResourcesPayload

    @GET("api/permissions/users/{id}/grants")
    suspend fun grants(@Path("id") userId: String): Grants

    @POST("api/permissions/users/{id}/route-grants")
    suspend fun grantRoute(@Path("id") userId: String, @Body body: RouteGrantBody)

    @DELETE("api/permissions/route-grants/{grantId}")
    suspend fun revokeRoute(@Path("grantId") grantId: String)

    @POST("api/permissions/users/{id}/resource-grants")
    suspend fun grantResource(@Path("id") userId: String, @Body body: ResourceGrantBody)

    @DELETE("api/permissions/resource-grants/{grantId}")
    suspend fun revokeResource(@Path("grantId") grantId: String)
}
