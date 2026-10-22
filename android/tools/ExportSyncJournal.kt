import com.hardwareshop.pos.data.sync.SyncJournalSchema
import com.hardwareshop.pos.data.sync.SyncTransferSchema
import java.util.Base64

// Emits the actual production SQL, not a test-side reimplementation.
fun main(args: Array<String>) {
    val statements = if (args.firstOrNull() == "acknowledge") listOf(SyncTransferSchema.completeProjectionSql)
        else SyncJournalSchema.statements() + SyncTransferSchema.statements()
    statements.forEach {
        println(Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)))
    }
}
