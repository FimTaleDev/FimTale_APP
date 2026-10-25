package com.fimtale.ui;

import com.fimtale.utils.MdiIcons;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.fimtale.MainActivity;
import com.fimtale.R;
import com.fimtale.adapter.BannerAdapter;
import com.fimtale.adapter.TopicAdapter;
import com.fimtale.model.RecommendedTopic;
import com.fimtale.model.Topic;
import com.fimtale.model.TopicViewItem;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.List;

import com.fimtale.network.RetrofitClient;
import com.fimtale.utils.DialogHelper;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class HomeFragment extends Fragment {
    private com.fimtale.ui.PageErrorView pageError;

    private PullRefreshLayout swipeRefreshLayout;
    private RecyclerView homeList;
    private LinearLayout emptyStateLayout;
    private LinearLayout quickAccessLayout;
    private LinearLayout btnGallery;
    private LinearLayout btnPosts;
    private LinearLayout btnTags;
    private LinearLayout btnChannels;
    private Button btnLogin;
    private TabLayout tabLayout;
    private ViewPager2 bannerViewPager;
    private ShimmerSkeletonView loadingSkeleton;
    private final List<Call<?>> homeCalls = new ArrayList<>();
    private int pendingHomeRequests;
    private String homeError;
    private TextView errorTextView;
    private Button viewMoreButton;
    private BannerAdapter bannerAdapter;
    private TopicAdapter topicAdapter;
    private final List<TopicViewItem> visibleTopics = new ArrayList<>();
    private int selectedTab;
    private List<RecommendedTopic> bannerList = new ArrayList<>();
    private List<RecommendedTopic> pendingBanners;
    private List<TopicViewItem> topicListHot = new ArrayList<>();
    private List<TopicViewItem> topicListNew = new ArrayList<>();
    private BannerAutoScroll bannerAutoScroll;
    private View rootView;
    private long editorVersion = com.fimtale.editor.EditorChanges.version();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        if (rootView == null) {
            rootView = inflater.inflate(R.layout.fragment_home, container, false);
        } else {
            ViewGroup parent = (ViewGroup) rootView.getParent();
            if (parent != null) {
                parent.removeView(rootView);
            }
        }
        return rootView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        requireActivity().addMenuProvider(new androidx.core.view.MenuProvider() {
            @Override public void onCreateMenu(@NonNull android.view.Menu menu, @NonNull android.view.MenuInflater inflater) {
                MdiIcons.inflateMenu(requireContext(), inflater, R.menu.home_menu, menu);
            }
            @Override public boolean onMenuItemSelected(@NonNull android.view.MenuItem item) {
                if (item.getItemId() == R.id.action_notifications) {
                    startActivity(new Intent(requireContext(), com.fimtale.NotificationsActivity.class)); return true;
                }
                if (item.getItemId() != R.id.action_search) return false;
                startActivity(new Intent(requireContext(), com.fimtale.SearchActivity.class)); return true;
            }
        }, getViewLifecycleOwner(), androidx.lifecycle.Lifecycle.State.RESUMED);

        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout);
        pageError = com.fimtale.ui.PageErrorView.wrap(swipeRefreshLayout);
        homeList = view.findViewById(R.id.homeList);
        // The XML installs its LayoutManager before these inflations generate row LayoutParams.
        View header = getLayoutInflater().inflate(R.layout.item_home_header, homeList, false);
        View footer = getLayoutInflater().inflate(R.layout.item_home_footer, homeList, false);
        bannerViewPager = header.findViewById(R.id.bannerViewPager);
        quickAccessLayout = header.findViewById(R.id.quickAccessLayout);
        btnGallery = header.findViewById(R.id.btnGallery);
        btnPosts = header.findViewById(R.id.btnPosts);
        btnTags = header.findViewById(R.id.btnTags);
        btnChannels = header.findViewById(R.id.btnChannels);
        loadingSkeleton = view.findViewById(R.id.loadingSkeleton);
        loadingSkeleton.setSkeletonLayout(ShimmerSkeletonView.Layout.HOME);
        errorTextView = view.findViewById(R.id.errorTextView);
        tabLayout = header.findViewById(R.id.tabLayout);
        viewMoreButton = footer.findViewById(R.id.viewMoreButton);
        emptyStateLayout = view.findViewById(R.id.emptyStateLayout);
        btnLogin = view.findViewById(R.id.btnLogin);

        setupBannerViewPager();
        setupRecyclerView(header, footer);
        setupTabLayout();
        setupSwipeRefresh();
        setupEmptyState();
        setupQuickAccess();

        loadContent();
    }

    private void setupQuickAccess() {
        btnGallery.setOnClickListener(v -> {
            Intent intent = new Intent(getContext(), com.fimtale.TagArticlesActivity.class);
            intent.putExtra(com.fimtale.TagArticlesActivity.EXTRA_WORK_TYPE, 2);
            startActivity(intent);
        });

        btnPosts.setOnClickListener(v -> {
            Intent intent = new Intent(getContext(), com.fimtale.TagArticlesActivity.class);
            intent.putExtra(com.fimtale.TagArticlesActivity.EXTRA_WORK_TYPE, 3);
            startActivity(intent);
        });

        btnTags.setOnClickListener(v -> {
            Intent intent = new Intent(getContext(), com.fimtale.TagListActivity.class);
            startActivity(intent);
        });

        btnChannels.setOnClickListener(v -> startActivity(new Intent(requireContext(), com.fimtale.channels.ChannelDirectoryActivity.class)));
    }

    private void setupEmptyState() {
        btnLogin.setOnClickListener(v -> {
            DialogHelper.openLogin(requireContext());
        });
    }

    private void loadContent() {
        emptyStateLayout.setVisibility(View.GONE);
        swipeRefreshLayout.setVisibility(View.VISIBLE);
        fetchHomePageData();
    }

    private void setupSwipeRefresh() {
        swipeRefreshLayout.setOnChildScrollUpCallback((parent, child) -> pendingHomeRequests > 0 || homeList.canScrollVertically(-1));
        swipeRefreshLayout.setOnRefreshListener(() -> {
            fetchHomePageData();
        });
    }

    private void setupBannerViewPager() {
        bannerAdapter = new BannerAdapter(bannerList, topic -> {
            Intent intent = new Intent(getContext(), com.fimtale.TopicDetailActivity.class);
            intent.putExtra(com.fimtale.TopicDetailActivity.EXTRA_TOPIC_ID, topic.getId());
            startActivity(intent);
        });
        bannerViewPager.setAdapter(bannerAdapter);
        bannerViewPager.setClipToPadding(false);
        bannerViewPager.setClipChildren(false);
        bannerViewPager.setOffscreenPageLimit(1);
        bannerViewPager.setPageTransformer(null);
        bannerAutoScroll = new BannerAutoScroll(bannerViewPager);
    }

    private void setupTabLayout() {
        TabLayout.Tab selected = tabLayout.getTabAt(selectedTab);
        if (selected != null) selected.select();
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                if (selectedTab == tab.getPosition()) return;
                selectedTab = tab.getPosition();
                displaySelectedTopics();
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}

            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });
    }

    private void setupRecyclerView(View header, View footer) {
        homeList.setHasFixedSize(true);
        topicAdapter = new TopicAdapter(visibleTopics);
        // One viewport-bound list: header and footer scroll with the cards without
        // measuring both entire feeds inside an unbounded NestedScrollView.
        homeList.setAdapter(new ConcatAdapter(new StaticRowAdapter(header), topicAdapter, new StaticRowAdapter(footer)));
        homeList.setItemAnimator(null);
        int cardInset = Math.round(8 * getResources().getDisplayMetrics().density);
        homeList.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override public void getItemOffsets(@NonNull android.graphics.Rect outRect, @NonNull View view,
                                                @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
                if (parent.getChildViewHolder(view).getBindingAdapter() == topicAdapter)
                    outRect.set(cardInset, 0, cardInset, 0);
            }
        });

        viewMoreButton.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                MainActivity mainActivity = (MainActivity) getActivity();
                BottomNavigationView bottomNav = mainActivity.findViewById(R.id.bottom_navigation);
                bottomNav.setSelectedItemId(R.id.nav_article);
            }
        });
    }

    private void displaySelectedTopics() {
        visibleTopics.clear();
        visibleTopics.addAll(selectedTab == 0 ? topicListHot : topicListNew);
        topicAdapter.notifyDataSetChanged();
    }

    private static final class StaticRowAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final View row;
        StaticRowAdapter(View row) { this.row = row; }
        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new RecyclerView.ViewHolder(row) {};
        }
        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {}
        @Override public int getItemCount() { return 1; }
    }

    private int homeRequest;
    private void fetchHomePageData() {
        pageError.hide();
        final int request = ++homeRequest;
        for (Call<?> call : homeCalls) call.cancel();
        homeCalls.clear(); pendingHomeRequests = 3; homeError = null; pendingBanners = null;
        stopBannerAutoScroll();
        boolean pulling = swipeRefreshLayout.isRefreshing();
        loadingSkeleton.setVisibility(pulling ? View.GONE : View.VISIBLE);
        errorTextView.setVisibility(View.GONE);
        homeList.setVisibility(pulling ? View.VISIBLE : View.INVISIBLE);
        homeList.scrollToPosition(0);
        quickAccessLayout.setVisibility(View.VISIBLE);
        viewMoreButton.setVisibility(View.VISIBLE);
        Call<com.fimtale.model.TopicListResponse> feed = RetrofitClient.getInstance().getFeed(1);
        homeCalls.add(feed);
        feed.enqueue(new Callback<com.fimtale.model.TopicListResponse>() {
            @Override public void onResponse(Call<com.fimtale.model.TopicListResponse> call, Response<com.fimtale.model.TopicListResponse> response) {
                if (!acceptHomeResult(request)) return;
                if (response.isSuccessful() && response.body() != null) {
                    topicListHot.clear();
                    List<Topic> topics = response.body().getTopicArray();
                    if (topics != null) for (Topic topic : topics) topicListHot.add(new TopicViewItem(topic));
                } else homeError = getString(R.string.home_recommendations_failed);
                finishHomeRequest();
            }
            @Override public void onFailure(Call<com.fimtale.model.TopicListResponse> call, Throwable t) {
                if (!acceptHomeResult(request)) return;
                homeError = getString(R.string.home_recommendations_failed); finishHomeRequest();
            }
        });
        Call<com.fimtale.model.TopicListResponse> latest = RetrofitClient.getInstance()
                .getTopicList(1, null, com.fimtale.network.SearchQuery.rank("last_chapter_at"));
        homeCalls.add(latest);
        latest.enqueue(new Callback<com.fimtale.model.TopicListResponse>() {
            @Override public void onResponse(Call<com.fimtale.model.TopicListResponse> call, Response<com.fimtale.model.TopicListResponse> response) {
                if (!acceptHomeResult(request)) return;
                if (response.isSuccessful() && response.body() != null) {
                    topicListNew.clear();
                    List<Topic> topics = response.body().getTopicArray();
                    if (topics != null) for (Topic topic : topics) topicListNew.add(new TopicViewItem(topic));
                } else homeError = getString(R.string.home_recent_updates_failed);
                finishHomeRequest();
            }
            @Override public void onFailure(Call<com.fimtale.model.TopicListResponse> call, Throwable t) {
                if (!acceptHomeResult(request)) return;
                homeError = getString(R.string.home_recent_updates_failed); finishHomeRequest();
            }
        });
        Call<com.fimtale.model.CuratedResponse> curated = RetrofitClient.getInstance().getCuratedWorks(1, 5);
        homeCalls.add(curated);
        curated.enqueue(new Callback<com.fimtale.model.CuratedResponse>() {
            @Override public void onResponse(Call<com.fimtale.model.CuratedResponse> call, Response<com.fimtale.model.CuratedResponse> response) {
                if (!acceptHomeResult(request)) return;
                if (response.isSuccessful() && response.body() != null) {
                    // Keep the attached adapter's data unchanged until the batch is ready.
                    pendingBanners = response.body().items == null ? new ArrayList<>() : new ArrayList<>(response.body().items);
                } else homeError = getString(R.string.home_curated_failed);
                finishHomeRequest();
            }
            @Override public void onFailure(Call<com.fimtale.model.CuratedResponse> call, Throwable t) {
                if (!acceptHomeResult(request)) return;
                homeError = getString(R.string.home_curated_failed); finishHomeRequest();
            }
        });
    }
    private boolean acceptHomeResult(int request) {
        return isAdded() && getView() != null && request == homeRequest;
    }
    private void finishHomeRequest() {
        if (--pendingHomeRequests != 0) return;
        homeCalls.clear();
        swipeRefreshLayout.setRefreshing(false);
        displaySelectedTopics();
        if (pendingBanners != null) {
            bannerList.clear(); bannerList.addAll(pendingBanners); pendingBanners = null;
            bannerAdapter.notifyDataSetChanged();
        }
        loadingSkeleton.setVisibility(View.GONE); homeList.setVisibility(View.VISIBLE);
        bannerViewPager.setVisibility(bannerList.isEmpty() ? View.GONE : View.VISIBLE);
        if (!bannerList.isEmpty()) {
            bannerViewPager.setCurrentItem(0, false);
            if (isResumed()) startBannerAutoScroll();
        }
        if (homeError != null) {
            pageError.show(homeError.replace(getString(R.string.common_retry_suffix), ""), this::fetchHomePageData,
                    !bannerList.isEmpty() || !visibleTopics.isEmpty());
        }
    }

    private void startBannerAutoScroll() {
        if (bannerAutoScroll != null && isResumed() && pendingHomeRequests == 0) bannerAutoScroll.start();
    }

    private void stopBannerAutoScroll() {
        if (bannerAutoScroll != null) bannerAutoScroll.stop();
    }

    @Override
    public void onPause() {
        super.onPause();
        stopBannerAutoScroll();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (editorVersion != com.fimtale.editor.EditorChanges.version()) {
            editorVersion = com.fimtale.editor.EditorChanges.version(); loadContent();
        }
        if (emptyStateLayout != null && emptyStateLayout.getVisibility() == View.VISIBLE ) {
            loadContent();
        }
        if (pendingHomeRequests == 0 && bannerAdapter != null && bannerAdapter.getItemCount() > 0) {
            startBannerAutoScroll();
        }
    }
    @Override public void onDestroyView() {
        homeRequest++;
        for (Call<?> call : homeCalls) call.cancel();
        homeCalls.clear(); pendingHomeRequests = 0; pendingBanners = null;
        if (bannerAutoScroll != null) { bannerAutoScroll.close(); bannerAutoScroll = null; }
        if (homeList != null) homeList.setAdapter(null);
        if (bannerViewPager != null) { bannerViewPager.setAdapter(null); bannerViewPager = null; }
        rootView = null; homeList = null;
        super.onDestroyView();
    }

}
