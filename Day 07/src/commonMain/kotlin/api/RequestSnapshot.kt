package api

data class RequestSnapshot(
    val metaJson: String,
    val historyCount: Int,
    val historyJson: String,
    val freshSummaryJson: String?,  // summary created during THIS message's send, null otherwise
    val currentJson: String
)
