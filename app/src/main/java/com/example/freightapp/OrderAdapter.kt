package com.example.freightapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.freightapp.repos.OrderActionListener
import java.text.SimpleDateFormat
import java.util.*

class OrderAdapter(
    private val orders: List<Order>,
    private val listener: OrderActionListener
) : RecyclerView.Adapter<OrderAdapter.OrderViewHolder>() {

    class OrderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvOrigin: TextView = itemView.findViewById(R.id.tvOrderOrigin)
        val tvDestination: TextView = itemView.findViewById(R.id.tvOrderDestination)
        val tvPrice: TextView = itemView.findViewById(R.id.tvOrderPrice)
        val tvStatus: TextView = itemView.findViewById(R.id.tvOrderStatus)
        val tvDate: TextView = itemView.findViewById(R.id.tvOrderDate)
        val tvDriverInfo: TextView = itemView.findViewById(R.id.tvDriverInfo)
        val btnEdit: Button = itemView.findViewById(R.id.btnEditOrder)
        val btnDelete: Button = itemView.findViewById(R.id.btnDeleteOrder)
        val btnContactDriver: Button = itemView.findViewById(R.id.btnContactDriver)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OrderViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_order, parent, false)
        return OrderViewHolder(view)
    }

    override fun onBindViewHolder(holder: OrderViewHolder, position: Int) {
        val order = orders[position]
        val context = holder.itemView.context

        // Set basic order information
        holder.tvOrigin.text = "From: ${order.originCity}"
        holder.tvDestination.text = "To: ${order.destinationCity}"
        holder.tvPrice.text = "$${String.format("%.2f", order.totalPrice)}"

        // Format date from timestamp
        val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
        val dateStr = dateFormat.format(Date(order.timestamp))
        holder.tvDate.text = "Created: $dateStr"

        // Set status with color coding
        holder.tvStatus.text = "Status: ${order.status}"
        when (order.status) {
            "Looking For Driver" -> {
                holder.tvStatus.setTextColor(ContextCompat.getColor(context, R.color.purple_500))
            }
            "Accepted" -> {
                holder.tvStatus.setTextColor(ContextCompat.getColor(context, R.color.teal_700))
            }
            "In Progress" -> {
                holder.tvStatus.setTextColor(ContextCompat.getColor(context, R.color.teal_700))
            }
            "Delivered", "Completed" -> {
                holder.tvStatus.setTextColor(ContextCompat.getColor(context, android.R.color.holo_green_dark))
            }
            "No Drivers Available", "Cancelled" -> {
                holder.tvStatus.setTextColor(ContextCompat.getColor(context, android.R.color.holo_red_dark))
            }
            else -> {
                holder.tvStatus.setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
            }
        }

        // Show/hide driver info based on assignment status
        if (order.driverUid != null) {
            holder.tvDriverInfo.visibility = View.VISIBLE
            holder.tvDriverInfo.text = "Driver assigned: ID ${order.driverUid!!.take(8)}..."
            holder.btnContactDriver.visibility = View.VISIBLE
        } else {
            holder.tvDriverInfo.visibility = View.GONE
            holder.btnContactDriver.visibility = View.GONE
        }

        // Enable/disable buttons based on order status
        val isEditable = order.status == "Pending"
        order.status == "No Drivers Available"
        order.status == "Looking For Driver"

        holder.btnEdit.isEnabled = isEditable
        holder.btnEdit.setOnClickListener {
            listener.onEditOrder(order)
        }

        holder.btnDelete.isEnabled = isEditable
        holder.btnDelete.setOnClickListener {
            listener.onDeleteOrder(order)
        }


        holder.btnDelete.setOnClickListener {
            listener.onDeleteOrder(order)
        }

        holder.btnContactDriver.setOnClickListener {
            listener.onContactDriver(order)
        }

        // Item click listener for viewing details
        holder.itemView.setOnClickListener {
            // You could add a detail view here, or expand the item to show more details
        }
    }

    override fun getItemCount(): Int = orders.size
}