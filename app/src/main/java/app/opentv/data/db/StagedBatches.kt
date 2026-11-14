/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.db

import java.io.Closeable
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Batches of rows decoded ahead of the write that saves them, kept on disk a batch to a line: so a
 * list too big for a Fire TV Stick's heap can be decoded whole before its transaction opens, rather
 * than inside it with SQLite's writer held the whole time (#328). [count] is how many rows were
 * staged. Close it to delete the file.
 */
class StagedBatches<T> private constructor(
    private val file: File,
    private val serializer: KSerializer<List<T>>,
    val count: Int,
) : Closeable {

    /** Each staged batch, in the order it was staged. */
    suspend fun forEachBatch(block: suspend (List<T>) -> Unit) {
        file.bufferedReader().use { lines ->
            while (true) {
                val line = lines.readLine() ?: break
                block(json.decodeFromString(serializer, line))
            }
        }
    }

    override fun close() {
        file.delete()
    }

    companion object {
        private val json = Json

        /**
         * Stages each batch [fill] hands to its `stage` in a new file in [dir] (null is the JVM's temp
         * folder). If [fill] throws, as a list cut off partway does, the file is deleted and the
         * exception passed on, so nothing half-decoded is left to be saved or to fill the disk.
         */
        suspend fun <T> stage(
            dir: File?,
            serializer: KSerializer<T>,
            fill: suspend (stage: (List<T>) -> Unit) -> Unit,
        ): StagedBatches<T> = withContext(Dispatchers.IO) {
            val batches = ListSerializer(serializer)
            val file = File.createTempFile("staged-", ".jsonl", dir)
            var staged: StagedBatches<T>? = null
            try {
                var count = 0
                file.bufferedWriter().use { out ->
                    fill { batch ->
                        if (batch.isEmpty()) return@fill
                        // One line a batch: the encoder escapes any line break inside a value.
                        out.write(json.encodeToString(batches, batch))
                        out.newLine()
                        count += batch.size
                    }
                }
                staged = StagedBatches(file, batches, count)
                staged
            } finally {
                if (staged == null) file.delete()
            }
        }
    }
}
