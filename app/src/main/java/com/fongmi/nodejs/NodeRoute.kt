package com.fongmi.nodejs

object NodeRoute {
    private const val PREFIX = "node:"

    @JvmStatic
    fun append(api: String?, endpoint: String?): String {
        require(!api.isNullOrEmpty() && api.startsWith(PREFIX)) { "Invalid Node API: $api" }
        var value = api.substring(PREFIX.length)
        val fragment = value.indexOf('#')
        if (fragment >= 0) value = value.substring(0, fragment)
        val query = value.indexOf('?')
        val suffix = if (query < 0) "" else value.substring(query)
        var path = if (query < 0) value else value.substring(0, query)
        if (!path.startsWith("/")) path = "/$path"
        while (path.endsWith("/") && path.length > 1) path = path.substring(0, path.length - 1)
        var child = endpoint?.trim().orEmpty()
        if (child.isNotEmpty() && !child.startsWith("/")) child = "/$child"
        if (path == "/" && child.isNotEmpty()) path = ""
        return PREFIX + path + child + suffix
    }

    @JvmStatic
    fun path(api: String?): String {
        require(!api.isNullOrEmpty() && api.startsWith(PREFIX)) { "Invalid Node API: $api" }
        val route = api.substring(PREFIX.length)
        return if (route.startsWith("/")) route else "/$route"
    }
}

