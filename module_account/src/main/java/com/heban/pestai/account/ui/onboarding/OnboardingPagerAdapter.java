package com.heban.pestai.account.ui.onboarding;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.heban.pestai.account.databinding.ItemAccountOnboardingPageBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * A02 三屏适配器（ViewPager2 内部基于横向 RecyclerView，三屏都很轻，不用 FragmentStateAdapter）。
 */
public class OnboardingPagerAdapter extends RecyclerView.Adapter<OnboardingPagerAdapter.PageHolder> {

    private final List<OnboardingPage> pages = new ArrayList<>();

    public void submit(@NonNull List<OnboardingPage> list) {
        pages.clear();
        pages.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public PageHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemAccountOnboardingPageBinding binding = ItemAccountOnboardingPageBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new PageHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull PageHolder holder, int position) {
        holder.binding.setPage(pages.get(position));
        holder.binding.executePendingBindings();
    }

    @Override
    public int getItemCount() {
        return pages.size();
    }

    static final class PageHolder extends RecyclerView.ViewHolder {
        final ItemAccountOnboardingPageBinding binding;

        PageHolder(@NonNull ItemAccountOnboardingPageBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
