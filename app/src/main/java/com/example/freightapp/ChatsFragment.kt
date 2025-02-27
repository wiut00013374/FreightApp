package com.example.freightapp

import android.os.Bundle
import android.view.*
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.freightapp.model.Chat
import com.example.freightapp.model.Message
import com.example.freightapp.repos.ChatRepository
import com.google.firebase.auth.FirebaseAuth

/**
 * ChatsFragment handles:
 * 1. Displaying a list of all chat conversations when no chatId is provided
 * 2. Displaying a specific chat conversation when a chatId is provided
 */
class ChatsFragment : Fragment() {

    companion object {
        private const val ARG_CHAT_ID = "arg_chat_id"

        fun newInstance(chatId: String? = null): ChatsFragment {
            val fragment = ChatsFragment()
            if (chatId != null) {
                val args = Bundle()
                args.putString(ARG_CHAT_ID, chatId)
                fragment.arguments = args
            }
            return fragment
        }
    }

    private var chatId: String? = null
    private var isInChatMode = false

    // Views for a specific chat conversation
    private lateinit var rvMessages: RecyclerView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: Button
    private val messagesList = mutableListOf<Message>()
    private lateinit var messageAdapter: MessageAdapter

    // Views for the chat list
    private lateinit var rvChats: RecyclerView
    private val chatsList = mutableListOf<Chat>()
    private lateinit var chatAdapter: ChatAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chatId = arguments?.getString(ARG_CHAT_ID)
        isInChatMode = chatId != null
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Use different layouts based on mode
        return if (isInChatMode) {
            inflater.inflate(R.layout.fragment_chats, container, false)
        } else {
            inflater.inflate(R.layout.fragment_chats_list, container, false)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (isInChatMode) {
            setupChatConversation(view)
        } else {
            setupChatsList(view)
        }
    }

    private fun setupChatConversation(view: View) {
        rvMessages = view.findViewById(R.id.rvMessages)
        etMessage = view.findViewById(R.id.etMessage)
        btnSend = view.findViewById(R.id.btnSend)

        // Setup RecyclerView
        rvMessages.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true // Messages start from the bottom
        }
        messageAdapter = MessageAdapter(messagesList, requireContext())
        rvMessages.adapter = messageAdapter

        // Check if we have a valid chat ID
        val chatIdValue = chatId
        if (chatIdValue.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "Invalid chat session", Toast.LENGTH_SHORT).show()
            parentFragmentManager.popBackStack()
            return
        }

        // Listen for messages in this chat
        ChatRepository.listenForMessages(chatIdValue) { newMessages ->
            messagesList.clear()
            messagesList.addAll(newMessages)
            messageAdapter.notifyDataSetChanged()

            // Scroll to bottom after messages load
            if (newMessages.isNotEmpty()) {
                rvMessages.scrollToPosition(newMessages.size - 1)
            }

            // Mark messages as read
            ChatRepository.markMessagesAsRead(chatIdValue)
        }

        // Setup send button
        btnSend.setOnClickListener {
            val text = etMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                ChatRepository.sendMessage(chatIdValue, text) { success ->
                    if (!success) {
                        Toast.makeText(requireContext(), "Failed to send message", Toast.LENGTH_SHORT).show()
                    } else {
                        etMessage.setText("")
                    }
                }
            }
        }
    }

    private fun setupChatsList(view: View) {
        rvChats = view.findViewById(R.id.rvChatsList)
        rvChats.layoutManager = LinearLayoutManager(requireContext())

        // Create adapter with click listener
        chatAdapter = ChatAdapter(chatsList) { chat ->
            // Navigate to specific chat conversation
            val chatFragment = newInstance(chat.id)
            parentFragmentManager.beginTransaction()
                .replace(R.id.nav_host_fragment, chatFragment)
                .addToBackStack(null)
                .commit()
        }
        rvChats.adapter = chatAdapter

        // Get current user ID
        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid
        if (currentUserId != null) {
            // Listen for all chats involving the current user
            ChatRepository.listenForUserChats(currentUserId) { updatedChats ->
                chatsList.clear()
                chatsList.addAll(updatedChats)
                chatAdapter.notifyDataSetChanged()
            }
        } else {
            Toast.makeText(requireContext(), "Please sign in to view chats", Toast.LENGTH_SHORT).show()
        }
    }
}