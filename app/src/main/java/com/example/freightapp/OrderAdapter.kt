package com.example.freightapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.freightapp.repos.OrderActionListener


class OrderAdapter(
    private val orders: List<Order>,
    private val listener: OrderActionListener
) : RecyclerView.Adapter<OrderAdapter.OrderViewHolder>() {

    class OrderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvOrigin: TextView = itemView.findViewById(R.id.tvOrderOrigin)
        val tvDestination: TextView = itemView.findViewById(R.id.tvOrderDestination)
        val tvPrice: TextView = itemView.findViewById(R.id.tvOrderPrice)
        val tvStatus: TextView = itemView.findViewById(R.id.tvOrderStatus)
        val btnEdit: Button = itemView.findViewById(R.id.btnEditOrder)
        val btnDelete: Button = itemView.findViewById(R.id.btnDeleteOrder)

        // NEW: Contact Driver button
        val btnContactDriver: Button = itemView.findViewById(R.id.btnContactDriver)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OrderViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_order, parent, false)
        return OrderViewHolder(view)
    }

    override fun onBindViewHolder(holder: OrderViewHolder, position: Int) {
        val order = orders[position]

        holder.tvOrigin.text = order.originCity
        holder.tvDestination.text = order.destinationCity
        holder.tvPrice.text = "$${String.format("%.2f", order.totalPrice)}"
        holder.tvStatus.text = order.status

        // Edit & Delete
        holder.btnEdit.setOnClickListener {
            listener.onEditOrder(order)
        }
        holder.btnDelete.setOnClickListener {
            listener.onDeleteOrder(order)
        }
        holder.btnContactDriver.setOnClickListener {
            listener.onContactDriver(order)
        }


    }

    override fun getItemCount(): Int = orders.size
}

