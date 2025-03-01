package com.example.freightapp

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.freightapp.model.Message
import com.example.freightapp.repos.ChatRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query

class ChatActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: MessageAdapter
    private val messagesList = mutableListOf<Message>()

    private lateinit var etMessageInput: EditText
    private lateinit var btnSendMessage: Button
    private lateinit var tvChatTitle: TextView

    private var chatId: String? = null
    private var driverName: String = "Driver"

    private val firestore by lazy { FirebaseFirestore.getInstance() }
    private val auth by lazy { FirebaseAuth.getInstance() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        // Initialize views
        recyclerView = findViewById(R.id.recyclerViewChatMessages)
        etMessageInput = findViewById(R.id.etMessageInput)
        btnSendMessage = findViewById(R.id.btnSendMessage)
        tvChatTitle = findViewById(R.id.tvChatTitle)

        // Initialize RecyclerView and adapter
        adapter = MessageAdapter(messagesList, this)
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true // Start from the bottom
        }

        // Get chat ID from intent
        chatId = intent.getStringExtra("EXTRA_CHAT_ID")

        if (chatId == null) {
            Toast.makeText(this, "Invalid chat session", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Get driver information to display in the title
        getDriverInfo(chatId!!)

        // Listen for messages
        listenForMessages(chatId!!)

        // Set up send button
        btnSendMessage.setOnClickListener {
            sendMessage(chatId!!)
        }
    }

    private fun getDriverInfo(chatId: String) {
        firestore.collection("chats").document(chatId)
            .get()
            .addOnSuccessListener { document ->
                if (document != null && document.exists()) {
                    val driverUid = document.getString("driverUid")

                    // Get driver name directly from the chat document first
                    val chatDriverName = document.getString("driverName")
                    if (!chatDriverName.isNullOrEmpty()) {
                        driverName = chatDriverName
                        tvChatTitle.text = "Chat with $driverName"
                    }
                    // If we have a driver UID and no driver name in chat document, get driver details
                    else if (driverUid != null) {
                        firestore.collection("users").document(driverUid)
                            .get()
                            .addOnSuccessListener { userDoc ->
                                val name = userDoc.getString("displayName")
                                    ?: userDoc.getString("driverName")
                                    ?: "Driver"
                                driverName = name
                                tvChatTitle.text = "Chat with $driverName"

                                // Update the chat document with driver name for future reference
                                firestore.collection("chats").document(chatId)
                                    .update("driverName", name)
                            }
                    } else {
                        tvChatTitle.text = "Chat with Driver"
                    }
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Error loading chat info: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun listenForMessages(chatId: String) {
        firestore.collection("chats").document(chatId)
            .collection("messages")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Toast.makeText(this, "Error loading messages: ${e.message}", Toast.LENGTH_SHORT).show()
                    return@addSnapshotListener
                }

                val messages = snapshot?.documents?.mapNotNull { doc ->
                    doc.toObject(Message::class.java)?.apply {
                        id = doc.id
                    }
                } ?: emptyList()

                messagesList.clear()
                messagesList.addAll(messages)
                adapter.notifyDataSetChanged()

                // Scroll to bottom if there are messages
                if (messagesList.isNotEmpty()) {
                    recyclerView.scrollToPosition(messagesList.size - 1)
                }

                // Mark messages as read
                ChatRepository.markMessagesAsRead(chatId)
            }
    }

    private fun sendMessage(chatId: String) {
        val messageText = etMessageInput.text.toString().trim()
        if (messageText.isEmpty()) {
            Toast.makeText(this, "Cannot send empty message", Toast.LENGTH_SHORT).show()
            return
        }

        ChatRepository.sendMessage(chatId, messageText) { success ->
            if (success) {
                etMessageInput.text.clear()
            } else {
                Toast.makeText(this, "Failed to send message", Toast.LENGTH_SHORT).show()
            }
        }
    }
}