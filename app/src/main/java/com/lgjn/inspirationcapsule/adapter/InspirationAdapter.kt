package com.lgjn.inspirationcapsule.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.lgjn.inspirationcapsule.R
import com.lgjn.inspirationcapsule.data.Inspiration

class InspirationAdapter(
    private var items: List<Inspiration> = emptyList(),
    private val onLongClick: (Inspiration) -> Unit,
    private val onFloatClick: (Inspiration) -> Unit,
    private val onDeleteClick: (Inspiration) -> Unit,
    private val onItemClick: (Int) -> Unit = {}
) : RecyclerView.Adapter<InspirationAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvContent: TextView = view.findViewById(R.id.tvContent)
        val tvDate: TextView = view.findViewById(R.id.tvDate)
        val btnFloat: View = view.findViewById(R.id.btnFloat)
        val btnDelete: View = view.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_inspiration, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvContent.text = item.content
        holder.tvDate.text = item.formattedDate()

        // 点击侧边卡片聚焦到该卡片
        holder.itemView.setOnClickListener { onItemClick(holder.bindingAdapterPosition) }

        holder.itemView.setOnLongClickListener {
            onLongClick(item)
            true
        }
        holder.btnFloat.setOnClickListener { onFloatClick(item) }
        holder.btnDelete.setOnClickListener { onDeleteClick(item) }
    }

    override fun getItemCount(): Int = items.size

    fun updateData(newItems: List<Inspiration>) {
        items = newItems
        notifyDataSetChanged()
    }
}
