package com.myAllVideoBrowser.ui.component.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.appcompat.content.res.AppCompatResources
import androidx.databinding.DataBindingUtil
import androidx.recyclerview.widget.RecyclerView
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.room.entity.PageInfo
import com.myAllVideoBrowser.databinding.ItemTopPageBinding

class TopPageAdapter(
    context: Context,
    private var pageInfos: List<PageInfo>,
    private val itemListener: TopPagesListener
) : ArrayAdapter<TopPageAdapter.TopPageViewHolder>(context, R.layout.item_top_page) {

    /**
     * Maps the well-known brand bookmarks to a high-fidelity vector drawable so the home
     * screen always renders the curated icons from the reference design even before
     * favicons are fetched from the network. Falls back to the live favicon when present
     * and finally to a neutral globe.
     */
    private fun resolveBrandIcon(pageInfo: PageInfo): Int? {
        val link = pageInfo.link.lowercase()
        val name = pageInfo.name.lowercase()

        return when {
            "facebook.com" in link || name == "facebook" -> R.drawable.brand_facebook
            "instagram.com" in link || name == "instagram" -> R.drawable.brand_instagram
            "vimeo.com" in link || name == "vimeo" -> R.drawable.brand_vimeo
            "dailymotion.com" in link || name == "dailymotion" -> R.drawable.brand_dailymotion
            "tiktok.com" in link || name == "tiktok" -> R.drawable.brand_tiktok
            "twitter.com" in link || "x.com" in link || name == "twitter" || name == "x" -> R.drawable.brand_x
            "whatsapp.com" in link || name == "whatsapp" -> R.drawable.brand_whatsapp
            "zedge.net" in link || "ringtone" in name -> R.drawable.brand_ringtone
            else -> null
        }
    }

    override fun getView(position: Int, view: View?, parent: ViewGroup): View {
        val binding = if (view == null) {
            val inflater = LayoutInflater.from(parent.context)
            ItemTopPageBinding.inflate(inflater, parent, false)
        } else {
            DataBindingUtil.getBinding(view)
        }

        with(binding) {
            this?.pageInfo = pageInfos[position]
            this?.listener = itemListener

            val brandRes = resolveBrandIcon(pageInfos[position])
            when {
                brandRes != null -> {
                    this?.imgIcon?.setImageDrawable(
                        AppCompatResources.getDrawable(parent.context, brandRes)
                    )
                    this?.imgIcon?.imageTintList = null
                }
                this?.pageInfo?.faviconBitmap() != null -> {
                    this.imgIcon.setImageBitmap(pageInfo!!.faviconBitmap())
                    this.imgIcon.imageTintList = null
                }
                else -> {
                    val drawable = AppCompatResources.getDrawable(
                        parent.context, R.drawable.ic_browser
                    )
                    this?.imgIcon?.setImageDrawable(drawable)
                }
            }
            this?.executePendingBindings()
        }

        return binding!!.root
    }

    override fun getItemId(position: Int) = try {
        pageInfos[position].hashCode().toLong()
    } catch (e: Exception) {
        0
    }

    override fun getCount(): Int {
        return pageInfos.size
    }

    class TopPageViewHolder(val binding: ItemTopPageBinding) : RecyclerView.ViewHolder(binding.root)

    fun setData(pageInfos: List<PageInfo>) {
        this.pageInfos = pageInfos
        notifyDataSetChanged()
    }

    interface TopPagesListener {
        fun onItemClicked(pageInfo: PageInfo)

        /** Returns true if the long-press was consumed; default to a no-op. */
        fun onItemLongClicked(pageInfo: PageInfo): Boolean = false
    }
}
