package aces.webctrl.trendservice.core;
import javax.servlet.*;
import java.nio.*;
import java.nio.file.*;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;
import com.controlj.green.addonsupport.*;
import com.controlj.green.serviceframework.*;
import com.controlj.green.core.email.*;
public class Initializer implements ServletContextListener {
  /** Contains basic information about this addon */
  public volatile static AddOnInfo info = null;
  /** The name of this addon */
  private volatile static String name;
  /** Path to the private directory for this addon */
  private volatile static Path root;
  /** Where to store trended data points */
  private volatile static Path dataFile;
  /** Where to store email recipients */
  private volatile static Path emailInfoFile;
  /** Logger for this addon */
  private volatile static FileLogger logger;
  /** The main processing thread */
  private volatile static Thread mainThread = null;
  /** Becomes true when the servlet context is destroyed */
  public volatile static boolean stop = false;
  /** Stores trended cache size points */
  public final static ConcurrentSkipListSet<CacheSize> trend = new ConcurrentSkipListSet<CacheSize>();
  /** If the cache size increases above this amount, an email notification will be sent. */
  public final static int thresh = 2500;
  /** Specifies a buffer which delays spurious notifications */
  public final static int tries = 3;
  /**
   * @return The current TrendHistorianService queue size.
   */
  public static CacheSize getQueueSize(){
    final Service s = ServiceManager.lookup("TrendHistorianService");
    return s==null?null:new CacheSize(s.getQueueSize());
  }
  /** Email subject for alarms */
  public volatile static String emailSubject = "Trend Alarm";
  /** List of email recipients. */
  public volatile static String[] emailRecipients = new String[]{};
  /**
   * Send an email.
   * @return whether the email was successfully sent.
   */
  public static boolean sendEmail(String message){
    final String sub = emailSubject;
    final String[] recip = emailRecipients;
    if (recip.length==0 || sub.isBlank()){
      return false;
    }
    try{
      EmailParametersBuilder pb = EmailServiceFactory.createParametersBuilder();
      pb.withSubject(sub);
      pb.withToRecipients(recip);
      pb.withMessageContents(message);
      pb.withMessageMimeType("text/plain");
      EmailServiceFactory.getService().sendEmail(pb.build());
      return true;
    }catch(Throwable t){
      log(t);
      return false;
    }
  }
  /**
   * Entry point of this add-on.
   */
  @Override public void contextInitialized(ServletContextEvent sce){
    info = AddOnInfo.getAddOnInfo();
    name = info.getName();
    root = info.getPrivateDir().toPath();
    dataFile = root.resolve("trend.data");
    emailInfoFile = root.resolve("email.info");
    logger = info.getDateStampLogger();
    mainThread = new Thread(){
      public void run(){
        try{
          Thread.sleep(2500L);
          if (stop){
            return;
          }
          if (Files.exists(dataFile)){
            final SerializationStream s = new SerializationStream(Files.readAllBytes(dataFile));
            while (!s.end() && !stop){
              trend.add(new CacheSize(s.readLong(), s.readInt()));
            }
            if (stop){
              return;
            }
          }
          if (Files.exists(emailInfoFile)){
            final SerializationStream s = new SerializationStream(Files.readAllBytes(emailInfoFile));
            emailSubject = s.readString();
            emailRecipients = new String[check(s.readInt(), 0, 4096, 0)];
            for (int i=0;i<emailRecipients.length;++i){
              emailRecipients[i] = s.readString();
            }
            if (stop){
              return;
            }
          }
        }catch(Throwable t){
          log(t);
        }
        CacheSize s;
        int k = 0;
        boolean good = true;
        int x = -tries;
        final int lowThresh = (int)(thresh*0.85+0.5);
        final int highThresh = (int)(thresh*1.15+0.5);
        while (!stop){
          try{
            while (!stop){
              if (k==0){
                k = 180;
                trimAndSave(true);
                if (stop){
                  return;
                }
              }
              --k;
              s = getQueueSize();
              if (s!=null){
                trend.add(s);
              }
              if (s.size<lowThresh){
                if (x>-tries){
                  --x;
                  if (!good && x==-tries){
                    good = true;
                    sendEmail("TrendHistorianService queue has returned to an acceptable size of "+s.size+" pending requests, which is under the "+thresh+" request threshold.");
                  }
                }
              }else if (s.size>highThresh){
                if (x<tries){
                  ++x;
                  if (good && x==tries){
                    good = false;
                    sendEmail("TrendHistorianService queue has exceeded the "+thresh+" request threshold with "+s.size+" pending requests.");
                  }
                }
              }
              if (stop){
                return;
              }
              Thread.sleep(15000L);
            }
          }catch(InterruptedException e){}
        }
      }
    };
    mainThread.start();
  }
  public static void trimAndSave(boolean kill){
    CacheSize s;
    Iterator<CacheSize> iter = trend.iterator();
    final long lim = System.currentTimeMillis()-259200000L;
    while (iter.hasNext()){
      s = iter.next();
      if (s.time<lim){
        iter.remove();
      }else{
        break;
      }
    }
    if (kill && stop){
      return;
    }
    SerializationStream stream = new SerializationStream(trend.size()*12, true);
    for (CacheSize cs: trend){
      stream.write(cs.time);
      stream.write(cs.size);
    }
    if (kill && stop){
      return;
    }
    ByteBuffer buf = stream.getBuffer();
    try(
      FileChannel out = FileChannel.open(dataFile, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    ){
      while (buf.hasRemaining()){
        out.write(buf);
      }
    }catch(Throwable t){
      log(t);
    }
    if (kill && stop){
      return;
    }
    buf = null;
    stream = new SerializationStream(512, true);
    stream.write(emailSubject);
    final String[] arr = emailRecipients;
    stream.write(arr.length);
    for (int i=0;i<arr.length;++i){
      stream.write(arr[i]);
    }
    if (kill && stop){
      return;
    }
    buf = stream.getBuffer();
    try(
      FileChannel out = FileChannel.open(emailInfoFile, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    ){
      while (buf.hasRemaining()){
        out.write(buf);
      }
    }catch(Throwable t){
      log(t);
    }
  }
  /**
   * Kills the primary processing thread and releases all resources.
   */
  @Override public void contextDestroyed(ServletContextEvent sce){
    stop = true;
    if (mainThread!=null){
      mainThread.interrupt();
      trimAndSave(false);
      try{
        mainThread.join();
      }catch(InterruptedException e){}
    }
  }
  /**
   * @return the name of this application.
   */
  public static String getName(){
    return name;
  }
  /**
   * Logs a message.
   */
  public synchronized static void log(String str){
    logger.println(str);
  }
  /**
   * Logs an error.
   */
  public synchronized static void log(Throwable t){
    logger.println(t);
  }
  /**
   * Internally used to check that loaded parameters are reasonable.
   */
  private final static int check(int x, int min, int max, int def){
    return x>=min&&x<=max?x:def;
  }
}