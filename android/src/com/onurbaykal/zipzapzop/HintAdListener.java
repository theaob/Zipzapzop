package com.onurbaykal.zipzapzop;

// How a rewarded hint ad ended: "reward", "closed" or "unavailable". Main thread.
interface HintAdListener {
    void hintAd(String result);
}
