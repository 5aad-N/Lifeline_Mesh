import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lifelinemesh.data.AppDatabase
import com.example.lifelinemesh.data.MessageDao
import com.example.lifelinemesh.data.MessageEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MessageDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var messageDao: MessageDao

    @Before
    fun createDb() {
        // Grab the application context
        val context = ApplicationProvider.getApplicationContext<Context>()

        // Initialize an in-memory database.
        // This is perfect for testing because it is completely wiped from RAM as soon as the test finishes,
        // leaving your actual app data completely untouched.
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries() // Allowed only for testing purposes
            .build()

        messageDao = database.messageDao()
    }

    @After
    fun closeDb() {
        // Clean up after the test completes
        database.close()
    }

    @Test
    fun testPriorityQueueOrdering() = runBlocking {
        // 1. Construct dummy payloads
        val standardMsg1 = MessageEntity(
            id = UUID.randomUUID().toString(),
            text = "Are you okay?",
            isFromMe = true,
            senderName = "Saad",
            senderPhone = "12345",
            latitude = null,
            longitude = null,
            timestamp = 1000L, // Oldest message
            priority = 1
        )

        val criticalGpsMsg = MessageEntity(
            id = UUID.randomUUID().toString(),
            text = "Emergency Location Shared",
            isFromMe = true,
            senderName = "Saad",
            senderPhone = "12345",
            latitude = 51.5072,
            longitude = -0.1276,
            timestamp = 2000L,
            priority = 3 // High priority GPS flag
        )

        val standardMsg2 = MessageEntity(
            id = UUID.randomUUID().toString(),
            text = "I am safe.",
            isFromMe = true,
            senderName = "Saad",
            senderPhone = "12345",
            latitude = null,
            longitude = null,
            timestamp = 3000L, // Newest message
            priority = 1
        )

        // 2. Insert the messages out of order.
        // We inject the GPS message last to prove the database actively sorts it,
        // rather than just returning it first because it was inserted first.
        messageDao.insertMessage(standardMsg1)
        messageDao.insertMessage(standardMsg2)
        messageDao.insertMessage(criticalGpsMsg)

        // 3. Fetch the transmission queue via your SQL query
        val queuedMessages = messageDao.getMessagesForForwarding()

        // 4. Execute Assertions (The actual validation)
        assertEquals("Queue should contain exactly 3 items", 3, queuedMessages.size)

        // Assert that the GPS message successfully jumped to index 0 (the front of the queue)
        assertEquals("Priority 3 message must be at the front of the queue", 3, queuedMessages[0].priority)
        assertEquals("Emergency Location Shared", queuedMessages[0].text)

        // Assert that standardMsg1 is at index 1 (Priority 1, but older timestamp than standardMsg2)
        assertEquals("Are you okay?", queuedMessages[1].text)
    }

    @Test
    fun testChronologicalOrderingForEqualPriority() = runBlocking {
        // 1. Create two standard messages with the SAME priority but DIFFERENT timestamps
        val olderMessage = MessageEntity(
            id = UUID.randomUUID().toString(),
            text = "First message sent",
            isFromMe = true,
            senderName = "Saad",
            senderPhone = "12345",
            latitude = null,
            longitude = null,
            timestamp = 1000L, // Older
            priority = 1
        )

        val newerMessage = MessageEntity(
            id = UUID.randomUUID().toString(),
            text = "Second message sent",
            isFromMe = true,
            senderName = "Saad",
            senderPhone = "12345",
            latitude = null,
            longitude = null,
            timestamp = 5000L, // Newer
            priority = 1
        )

        // 2. Insert out of order to ensure the database does the sorting
        messageDao.insertMessage(newerMessage)
        messageDao.insertMessage(olderMessage)

        // 3. Fetch the queue
        val queuedMessages = messageDao.getMessagesForForwarding()

        // 4. Assert that the older message is at the front (index 0)
        assertEquals("Queue should have 2 items", 2, queuedMessages.size)
        assertEquals("Older message must be forwarded first (FIFO)", "First message sent", queuedMessages[0].text)
        assertEquals("Newer message must be forwarded second", "Second message sent", queuedMessages[1].text)
    }

    @Test
    fun testDuplicateMessageHandling() = runBlocking {
        val sharedId = UUID.randomUUID().toString()

        // Create a message
        val originalMessage = MessageEntity(
            id = sharedId, // Hardcoded ID
            text = "I need water",
            isFromMe = false,
            senderName = "Survivor A",
            senderPhone = "999",
            latitude = null,
            longitude = null,
            timestamp = 1000L,
            priority = 1
        )

        // Simulate a network echo by trying to insert the exact same message twice
        messageDao.insertMessage(originalMessage)

        try {
            messageDao.insertMessage(originalMessage)
        } catch (e: Exception) {
            // If you don't have an OnConflictStrategy defined, SQLite might throw an exception.
            // That is still a form of database protection!
        }

        // Fetch all messages
        val queuedMessages = messageDao.getMessagesForForwarding()

        // Assert that the database only saved it ONCE
        assertEquals("Database must reject or overwrite duplicate UUIDs to prevent routing loops", 1, queuedMessages.size)
    }
}