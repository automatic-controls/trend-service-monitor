package aces.webctrl.trendservice.core;
public class CacheSize implements Comparable<CacheSize> {
  public volatile long time;
  public volatile int size;
  public CacheSize(int size){
    time = System.currentTimeMillis();
    this.size = size;
  }
  public CacheSize(long time, int size){
    this.time = time;
    this.size = size;
  }
  @Override public int compareTo(CacheSize x){
    return Long.compare(time,x.time);
  }
}