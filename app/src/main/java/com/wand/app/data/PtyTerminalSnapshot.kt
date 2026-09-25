package com.wand.app.data

import org.json.JSONObject

/** Server/Render v1 checkpoint: ANSI screen plus an ordered tail of writes and resizes. */
internal data class PtyTerminalSnapshot(
    val version: Int,
    val data: String,
    val cols: Int,
    val rows: Int,
    val pending: List<Operation>,
) {
    internal sealed interface Operation {
        data class Data(val text: String) : Operation
        data class Resize(val cols: Int, val rows: Int) : Operation
    }

    val isReplayable: Boolean get() = version == 1 && validSize(cols, rows)

    /** The checkpoint may end with a resize in its ordered pending operations. */
    val finalSize: Pair<Int, Int> get() = pending
        .filterIsInstance<Operation.Resize>()
        .lastOrNull()
        ?.let { it.cols to it.rows } ?: (cols to rows)

    companion object {
        fun validSize(cols: Int, rows: Int): Boolean = cols in 1..1000 && rows in 1..1000

        fun parse(value: JSONObject?): PtyTerminalSnapshot? {
            if (value == null) return null
            val version = value.optInt("version", -1)
            val cols = value.optInt("cols", 0)
            val rows = value.optInt("rows", 0)
            if (version != 1 || !validSize(cols, rows) || !value.has("data") ||
                value.isNull("data")) return null
            val pendingArray = value.optJSONArray("pending") ?: return null
            val operations = ArrayList<Operation>(pendingArray.length())
            for (index in 0 until pendingArray.length()) {
                val operation = pendingArray.optJSONObject(index) ?: return null
                when (operation.optString("type")) {
                    "data" -> {
                        if (!operation.has("data") || operation.isNull("data")) return null
                        operations.add(Operation.Data(operation.getString("data")))
                    }
                    "resize" -> {
                        val nextCols = operation.optInt("cols", 0)
                        val nextRows = operation.optInt("rows", 0)
                        if (!validSize(nextCols, nextRows)) return null
                        operations.add(Operation.Resize(nextCols, nextRows))
                    }
                    else -> return null
                }
            }
            return PtyTerminalSnapshot(version, value.getString("data"), cols, rows, operations)
        }
    }
}
