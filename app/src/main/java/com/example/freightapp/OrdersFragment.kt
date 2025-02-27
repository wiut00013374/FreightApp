package com.example.freightapp

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.*
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.freightapp.helpers.NominatimHelper
import com.example.freightapp.helpers.PriceCalculatorHelper
import com.example.freightapp.repos.ChatRepository
import com.example.freightapp.repos.OrderActionListener
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*

class OrdersFragment : Fragment(), OrderActionListener {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: OrderAdapter
    private val ordersList = mutableListOf<Order>()
    private lateinit var emptyView: TextView
    private lateinit var progressBar: ProgressBar

    companion object {
        private const val TAG = "OrdersFragment"
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_orders, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize views
        recyclerView = view.findViewById(R.id.recyclerViewOrders)
        emptyView = view.findViewById(R.id.tvNoOrders)
        progressBar = view.findViewById(R.id.progressBar)

        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        // Pass 'this' as the OrderActionListener
        adapter = OrderAdapter(ordersList, this)
        recyclerView.adapter = adapter

        // Show loading state
        showLoading(true)

        fetchOrders()
    }

    private fun fetchOrders() {
        val currentUid = FirebaseAuth.getInstance().currentUser?.uid
        if (currentUid == null) {
            Log.e(TAG, "User not authenticated!")
            showEmptyState("Please sign in to view orders")
            return
        }

        OrderRepository.listenForCustomerOrders(currentUid) { orders ->
            // Hide loading
            showLoading(false)

            if (orders.isEmpty()) {
                showEmptyState("You don't have any orders yet")
            } else {
                showOrders()
            }

            // Sort orders by timestamp (newest first)
            val sortedOrders = orders.sortedByDescending { it.timestamp }

            ordersList.clear()
            ordersList.addAll(sortedOrders)
            adapter.notifyDataSetChanged()
        }
    }

    private fun showLoading(isLoading: Boolean) {
        progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        recyclerView.visibility = View.GONE
        emptyView.visibility = View.GONE
    }

    private fun showEmptyState(message: String) {
        progressBar.visibility = View.GONE
        recyclerView.visibility = View.GONE
        emptyView.visibility = View.VISIBLE
        emptyView.text = message
    }

    private fun showOrders() {
        progressBar.visibility = View.GONE
        recyclerView.visibility = View.VISIBLE
        emptyView.visibility = View.GONE
    }

    // =========================================
    // OrderActionListener - Existing Methods
    // =========================================
    override fun onEditOrder(order: Order) {
        showEditOrderDialog(order)
    }override fun onDeleteOrder(order: Order) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Order")
            .setMessage("Are you sure you want to delete this order?")
            .setPositiveButton("Yes") { dialog, _ ->
                OrderRepository.deleteOrder(order.id) { success ->
                    Toast.makeText(
                        requireContext(),
                        if (success) "Order deleted!" else "Failed to delete order.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("No") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    // =========================================
    // NEW: ContactDriver from adapter
    // =========================================
    override fun onContactDriver(order: Order) {
        val customerUid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val driverUid = order.driverUid
        val orderId = order.id

        Log.d("onContactDriver", "driverUid=$driverUid orderId=$orderId customerUid=$customerUid")

        if (driverUid.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "No driver assigned yet!", Toast.LENGTH_SHORT).show()
            return
        }

        // Show loading indicator
        val loadingDialog = AlertDialog.Builder(requireContext())
            .setView(LayoutInflater.from(context).inflate(R.layout.dialog_loading, null))
            .setCancelable(false)
            .create()
        loadingDialog.show()

        ChatRepository.createOrGetChat(orderId, driverUid, customerUid) { chatId ->
            // Dismiss loading indicator
            loadingDialog.dismiss()

            Log.d("onContactDriver", "chatId=$chatId")
            if (chatId != null) {
                val fragment = ChatsFragment.newInstance(chatId)
                parentFragmentManager.beginTransaction()
                    .replace(R.id.nav_host_fragment, fragment)
                    .addToBackStack(null)
                    .commit()
            } else {
                Toast.makeText(requireContext(), "Failed to create chat", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showEditOrderDialog(order: Order) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_order, null)
        val actvOrigin = dialogView.findViewById<AutoCompleteTextView>(R.id.actvOriginEdit)
        val actvDestination = dialogView.findViewById<AutoCompleteTextView>(R.id.actvDestinationEdit)
        val etVolume = dialogView.findViewById<EditText>(R.id.etVolumeEdit)
        val etWeight = dialogView.findViewById<EditText>(R.id.etWeightEdit)
        val etTruckType = dialogView.findViewById<EditText>(R.id.etTruckTypeEdit)

        // Price Preview (added programmatically; you can put it in the XML if you prefer)
        val tvPricePreview = TextView(requireContext()).apply {
            textSize = 16f
        }

        // Pre-fill
        actvOrigin.setText(order.originCity)
        actvDestination.setText(order.destinationCity)
        etVolume.setText(order.volume.toString())
        etWeight.setText(order.weight.toString())
        etTruckType.setText(order.truckType)
        var currentOriginLat = order.originLat
        var currentOriginLon = order.originLon
        var currentDestLat = order.destinationLat
        var currentDestLon = order.destinationLon
        var currentVolume = order.volume
        var currentWeight = order.weight

        // Helper to recalc price
        fun recalcPriceAndDisplay() {
            val distanceMeters = PriceCalculatorHelper.computeDistanceMeters(
                currentOriginLat, currentOriginLon,
                currentDestLat, currentDestLon
            )
            val newPrice = PriceCalculatorHelper.calculatePrice(distanceMeters, currentWeight, currentVolume)
            tvPricePreview.text = "Price: $${String.format("%.2f", newPrice)}"
        }
        // initial calc
        recalcPriceAndDisplay()// ---------------------------------------
        // SUGGESTION LOGIC for ORIGIN
        // ---------------------------------------
        val handlerOrigin = Handler(Looper.getMainLooper())
        var originRunnable: Runnable? = null
        actvOrigin.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val query = s.toString().trim()
                if (query.length < 2) {
                    actvOrigin.dismissDropDown()
                    return
                }
                originRunnable?.let { handlerOrigin.removeCallbacks(it) }
                originRunnable = Runnable {
                    CoroutineScope(Dispatchers.IO).launch {
                        val suggestions = NominatimHelper.searchNominatimForward(query)
                        withContext(Dispatchers.Main) {
                            val adapter = ArrayAdapter(
                                requireContext(),
                                android.R.layout.simple_dropdown_item_1line,
                                suggestions.map { it.displayName }
                            )
                            actvOrigin.setAdapter(adapter)
                            actvOrigin.showDropDown()
                        }
                    }
                }
                handlerOrigin.postDelayed(originRunnable!!, 500)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        actvOrigin.setOnItemClickListener { parent, _, position, _ ->
            val selectedName = parent.getItemAtPosition(position) as String
            // fetch lat/lon for the EXACT match
            CoroutineScope(Dispatchers.IO).launch {
                val allSuggestions = NominatimHelper.searchNominatimForward(selectedName)
                val match = allSuggestions.find { it.displayName == selectedName }
                match?.let {
                    currentOriginLat = it.lat
                    currentOriginLon = it.lon
                }
                withContext(Dispatchers.Main) {
                    actvOrigin.setText(selectedName)
                    recalcPriceAndDisplay()
                }
            }
        }// ---------------------------------------
        // SUGGESTION LOGIC for DESTINATION
        // ---------------------------------------
        val handlerDest = Handler(Looper.getMainLooper())
        var destRunnable: Runnable? = null
        actvDestination.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val query = s.toString().trim()
                if (query.length < 2) {
                    actvDestination.dismissDropDown()
                    return
                }
                destRunnable?.let { handlerDest.removeCallbacks(it) }
                destRunnable = Runnable {
                    CoroutineScope(Dispatchers.IO).launch {
                        val suggestions = NominatimHelper.searchNominatimForward(query)
                        withContext(Dispatchers.Main) {
                            val adapter = ArrayAdapter(
                                requireContext(),
                                android.R.layout.simple_dropdown_item_1line,
                                suggestions.map { it.displayName }
                            )
                            actvDestination.setAdapter(adapter)
                            actvDestination.showDropDown()
                        }
                    }
                }
                handlerDest.postDelayed(destRunnable!!, 500)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        actvDestination.setOnItemClickListener { parent, _, position, _ ->
            val selectedName = parent.getItemAtPosition(position) as String
            CoroutineScope(Dispatchers.IO).launch {
                val allSuggestions = NominatimHelper.searchNominatimForward(selectedName)
                val match = allSuggestions.find { it.displayName == selectedName }
                match?.let {
                    currentDestLat = it.lat
                    currentDestLon = it.lon
                }
                withContext(Dispatchers.Main) {
                    actvDestination.setText(selectedName)
                    recalcPriceAndDisplay()
                }
            }
        }

        // ---------------------------------------
        // WATCHERS for volume/weight
        // ---------------------------------------
        etVolume.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                currentVolume = s?.toString()?.toDoubleOrNull() ?: order.volume
                recalcPriceAndDisplay()
            }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        etWeight.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                currentWeight = s?.toString()?.toDoubleOrNull() ?: order.weight
                recalcPriceAndDisplay()
            }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })

        // We'll add the dialogView + the price preview in a container
        val parentLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        parentLayout.addView(dialogView)
        parentLayout.addView(tvPricePreview)
        AlertDialog.Builder(requireContext())
            .setTitle("Edit Order")
            .setView(parentLayout)
            .setPositiveButton("Save") { dialog, _ ->
                val updatedOriginCity = actvOrigin.text.toString().trim()
                val updatedDestCity = actvDestination.text.toString().trim()
                val updatedTruckType = etTruckType.text.toString().trim()
                val vol = etVolume.text.toString().toDoubleOrNull() ?: order.volume
                val wgt = etWeight.text.toString().toDoubleOrNull() ?: order.weight

                // Final price with updated data
                val distanceMeters = PriceCalculatorHelper.computeDistanceMeters(
                    currentOriginLat, currentOriginLon,
                    currentDestLat, currentDestLon
                )
                val finalPrice = PriceCalculatorHelper.calculatePrice(distanceMeters, wgt, vol)

                // Build map of updates for Firestore
                val updates = mapOf(
                    "originCity" to updatedOriginCity,
                    "destinationCity" to updatedDestCity,
                    "originLat" to currentOriginLat,
                    "originLon" to currentOriginLon,
                    "destinationLat" to currentDestLat,
                    "destinationLon" to currentDestLon,
                    "volume" to vol,
                    "weight" to wgt,
                    "truckType" to updatedTruckType,
                    "totalPrice" to finalPrice
                )

                OrderRepository.updateOrder(order.id, updates) { success ->
                    val msg = if (success) "Order updated!" else "Failed to update order."
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                }

                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
}