package com.onurbaykal.zipzapzop;

// How a rewarded ad ended: "reward", "closed" or "unavailable". `purpose` is what the page asked it for
// ("hint" or "energy"), passed back unchanged. Main thread.
interface RewardedAdListener {
    void rewardedAd(String purpose, String result);
}
