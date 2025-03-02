package com.example.freightapp.fragments

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.freightapp.OrderTrackingActivity
import com.example.freightapp.R
import com.example.freightapp.model.Order
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class OrderDetailFragment : Fragment() {
    companion object {
        private const val ARG_ORDER_ID = "order_id"
        private const val TAG = "OrderDetailFragment"

        fun newInstance(orderId: String): OrderDetailFragment {
            val fragment = OrderDetailFragment()
            val args = Bundle()
            args.putString(ARG_ORDER_ID, orderId)
            fragment.arguments = args
            return fragment
        }
    }

    private var orderId: String? = null
    private var orderListener: ListenerRegistration? = null
    private lateinit var tvOrderId: TextView
    private lateinit var tvOrderStatus: TextView
    private lateinit var tvOriginCity: TextView
    private lateinit var tvDestinationCity: TextView
    private lateinit var tvTruckType: TextView
    private lateinit var tvPrice: TextView
    private lateinit var tvCreatedAt: TextView
    private lateinit var tvDriver: TextView
    private lateinit var btnTrackOrder: Button
    private lateinit var btnContactDriver: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        orderId = arguments?.getString(ARG_ORDER_ID)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_order_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize views
        tvOrderId = view.findViewById(R.id.tvOrderDetailId)
        tvOrderStatus = view.findViewById(R.id.tvOrderDetailStatus)
        tvOriginCity = view.findViewById(R.id.tvOrderDetailOrigin)
        tvDestinationCity = view.findViewById(R.id.tvOrderDetailDestination)
        tvTruckType = view.findViewById(R.id.tvOrderDetailTruckType)
        tvPrice = view.findViewById(R.id.tvOrderDetailPrice)
        tvCreatedAt = view.findViewById(R.id.tvOrderDetailCreatedAt)
        tvDriver = view.findViewById(R.id.tvOrderDetailDriver)
        btnTrackOrder = view.findViewById(R.id.btnTrackOrder)
        btnContactDriver = view.findViewById(R.id.btnContactDriver)

        // Set up order listener
        setupOrderListener()

        // Set up tracking button
        btnTrackOrder.setOnClickListener {
            startOrderTracking()
        }

        // Set up contact driver button
        btnContactDriver.setOnClickListener {
            contactDriver()
        }
    }

    private fun setupOrderListener() {
        val orderIdValue = orderId ?: return

        // Remove any existing listener
        orderListener?.remove()

        // Set up new listener
        orderListener = FirebaseFirestore.getInstance()
            .collection("orders")
            .document(orderIdValue)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error listening to order updates: ${error.message}")
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    val order = snapshot.toObject(Order::class.java)?.apply {
                        id = snapshot.id
                    }

                    if (order != null) {
                        updateUI(order)
                    }
                }
            }
    }

    private fun updateUI(order: Order) {
        // Update the UI with order details
        tvOrderId.text = "Order #${order.id.takeLast(8)}"
        tvOrderStatus.text = "Status: ${order.status}"
        tvOriginCity.text = "From: ${order.originCity}"
        tvDestinationCity.text = "To: ${order.destinationCity}"
        tvTruckType.text = "Truck: ${order.truckType}"
        tvPrice.text = "Price: $${String.format("%.2f", order.totalPrice)}"

        // Format timestamp to readable date
        val dateFormat = java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.getDefault())
        val createdDate = dateFormat.format(java.util.Date(order.timestamp))
        tvCreatedAt.text = "Created: $createdDate"

        // Update driver info
        if (order.driverUid != null) {
            fetchDriverInfo(order.driverUid!!)
            btnContactDriver.visibility = View.VISIBLE
        } else {
            tvDriver.text = "Driver: Not assigned yet"
            btnContactDriver.visibility = View.GONE
        }

        // Show/hide track button based on order status
        val canTrack = order.status in listOf("Accepted", "In Progress", "Picked Up") && order.driverUid != null
        btnTrackOrder.visibility = if (canTrack) View.VISIBLE else View.GONE

        // Update status text color based on status
        when (order.status) {
            "Pending", "Looking For Driver" -> {
                tvOrderStatus.setTextColor(requireContext().getColor(R.color.purple_500))
            }
            "Accepted", "In Progress" -> {
                tvOrderStatus.setTextColor(requireContext().getColor(R.color.teal_700))
            }
            "Picked Up" -> {
                tvOrderStatus.setTextColor(requireContext().getColor(android.R.color.holo_blue_dark))
            }
            "Delivered", "Completed" -> {
                tvOrderStatus.setTextColor(requireContext().getColor(android.R.color.holo_green_dark))
            }
            "Cancelled", "No Drivers Available" -> {
                tvOrderStatus.setTextColor(requireContext().getColor(android.R.color.holo_red_dark))
            }
            else -> {
                tvOrderStatus.setTextColor(requireContext().getColor(android.R.color.darker_gray))
            }
        }
    }

    private fun fetchDriverInfo(driverUid: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val driverDoc = FirebaseFirestore.getInstance()
                    .collection("users")
                    .document(driverUid)
                    .get()
                    .await()

                val driverName = driverDoc.getString("displayName") ?: "Driver"

                CoroutineScope(Dispatchers.Main).launch {
                    tvDriver.text = "Driver: $driverName"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching driver info: ${e.message}")
                CoroutineScope(Dispatchers.Main).launch {
                    tvDriver.text = "Driver: Information not available"
                }
            }
        }
    }

    private fun startOrderTracking() {
        val orderIdValue = orderId ?: return

        val intent = Intent(requireContext(), OrderTrackingActivity::class.java)
        intent.putExtra("EXTRA_ORDER_ID", orderIdValue)
        startActivity(intent)
    }

    private fun contactDriver() {
        // Start chat activity with the driver
        // Implementation will be similar to the one in OrderTrackingActivity
        Toast.makeText(requireContext(), "Contact driver functionality coming soon", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        orderListener?.remove()
    }
}