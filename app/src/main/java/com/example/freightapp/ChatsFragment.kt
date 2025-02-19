package com.example.freightapp

import android.os.Bundle
import android.view.*
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.freightapp.model.Message
import com.example.freightapp.repos.ChatRepository

class ChatsFragment : Fragment() {

    companion object {
        private const val ARG_CHAT_ID = "arg_chat_id"

        fun newInstance(chatId: String): ChatsFragment {
            val fragment = ChatsFragment()
            val args = Bundle()
            args.putString(ARG_CHAT_ID, chatId)
            fragment.arguments = args
            return fragment
        }
    }

    private var chatId: String? = null

    private lateinit var rvMessages: RecyclerView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: Button

    private val messagesList = mutableListOf<Message>()
    private lateinit var adapter: MessageAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chatId = arguments?.getString(ARG_CHAT_ID)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_chats, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvMessages = view.findViewById(R.id.rvMessages)
        etMessage = view.findViewById(R.id.etMessage)
        btnSend = view.findViewById(R.id.btnSend)

        val chatId = arguments?.getString(ARG_CHAT_ID)
        if (chatId.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "Invalid chat session", Toast.LENGTH_SHORT).show()
            parentFragmentManager.popBackStack()
            return
        }

        // set up adapter, RecyclerView...
        ChatRepository.listenForMessages(chatId) { newMessages ->
            messagesList.clear()
            messagesList.addAll(newMessages)
            adapter.notifyDataSetChanged()
        }

        btnSend.setOnClickListener {
            val text = etMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                ChatRepository.sendMessage(chatId, text) { success ->
                    if (!success) {
                        Toast.makeText(requireContext(), "Failed to send message", Toast.LENGTH_SHORT).show()
                    } else {
                        etMessage.setText("")
                    }
                }
            }
        }
    }

}
