package aces.webctrl.trendservice.web;
import aces.webctrl.trendservice.core.*;
import javax.servlet.http.*;
import java.io.*;
import java.util.*;
import java.util.regex.*;
public class MainPage extends ServletBase {
  private final static Pattern splitter = Pattern.compile(";", Pattern.LITERAL);
  @Override public void exec(final HttpServletRequest req, final HttpServletResponse res) throws Throwable {
    final String type = req.getParameter("type");
    if (type==null){
      res.setContentType("text/html");
      res.getWriter().print(getHTML(req)
        .replace("__EMAIL_SUBJECT__", Utility.escapeJS(Initializer.emailSubject))
        .replace("__EMAIL_RECIPIENTS__", Utility.escapeJS(String.join(";", Initializer.emailRecipients)))
      );
    }else{
      switch (type){
        case "load":{
          final String minutes = req.getParameter("milli");
          NavigableSet<CacheSize> set;
          if (minutes==null){
            set = Initializer.trend;
          }else{
            try{
              set = Initializer.trend.tailSet(new CacheSize(System.currentTimeMillis()-Long.parseLong(minutes)*60000L, 0));
            }catch(NumberFormatException e){
              set = Initializer.trend;
            }
          }
          res.setContentType("application/json");
          final PrintWriter p = res.getWriter();
          p.print('[');
          boolean first = true;
          for (CacheSize s: set){
            if (first){
              first = false;
            }else{
              p.print(',');
            }
            p.print(Utility.format("{\"x\":$0,\"y\":$1}", s.time, s.size));
          }
          p.print(']');
          break;
        }
        case "save":{
          final String emailSubject = req.getParameter("emailSubject");
          final String emailRecipients = req.getParameter("emailRecipients");
          if (emailSubject==null || emailRecipients==null){
            res.setStatus(400);
            return;
          }
          Initializer.emailRecipients = splitter.split(emailRecipients);
          Initializer.emailSubject = emailSubject;
          break;
        }
        default:{
          res.sendError(400, "Unrecognized type parameter.");
        }
      }
    }
  }
}