package com.lgjn.inspirationcapsule.data

data class Inspiration(
    val id: Long = 0,
    var title: String = "",
    val content: String = "",
    val audioPath: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    fun formattedDate(): String {
        val sdf = java.text.SimpleDateFormat("yyyy.MM.dd HH:mm", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(createdAt))
    }
}
