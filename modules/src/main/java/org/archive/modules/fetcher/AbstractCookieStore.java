/*
 *  This file is part of the Heritrix web crawler (crawler.archive.org).
 *
 *  Licensed to the Internet Archive (IA) by one or more individual 
 *  contributors. 
 *
 *  The IA licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.archive.modules.fetcher;

import it.unimi.dsi.mg4j.util.MutableString;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedList;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.io.IOUtils;
import org.apache.http.client.CookieStore;
import org.apache.http.cookie.Cookie;
import org.apache.http.cookie.CookieIdentityComparator;
import org.apache.http.impl.cookie.BasicClientCookie;
import org.archive.checkpointing.Checkpointable;
import org.archive.spring.ConfigFile;
import org.archive.spring.ConfigPath;
import org.json.JSONArray;
import org.springframework.context.Lifecycle;

public abstract class AbstractCookieStore implements CookieStore, Lifecycle, Closeable,
        Checkpointable {

    private static final Logger logger = 
        Logger.getLogger(AbstractCookieStore.class.getName());
    
    protected ConfigFile cookiesLoadFile = null;
    public ConfigFile getCookiesLoadFile() {
        return cookiesLoadFile;
    }
    public void setCookiesLoadFile(ConfigFile cookiesLoadFile) {
        this.cookiesLoadFile = cookiesLoadFile;
    }
    
    protected ConfigPath cookiesSaveFile = null;
    public ConfigPath getCookiesSaveFile() {
        return cookiesSaveFile;
    }
    public void setCookiesSaveFile(ConfigPath cookiesSaveFile) {
        this.cookiesSaveFile = cookiesSaveFile;
    }

    @Override
    public void close() throws IOException {
        // XXX only here because old BdbCookie also implements Closeable and does nothing... why?
    }

    protected boolean isRunning = false; 

    @Override
    public void start() {
        if (isRunning()) {
            return;
        }
        prepare();
        if (getCookiesLoadFile()!=null) {
            loadCookies(getCookiesLoadFile());
        }
        isRunning = true;
    }


    @Override
    public void stop() {
        isRunning = false; 
    }

    @Override
    public boolean isRunning() {
        return isRunning;
    }

    public void saveCookies() {
        if (getCookiesSaveFile()!=null) {
            saveCookies(getCookiesSaveFile().getFile().getAbsolutePath());
        }
    }
    
    protected void loadCookies(ConfigFile file) {
        Reader reader = null;
        try {
            reader = file.obtainReader();
            loadCookies(reader);
        } finally {
            IOUtils.closeQuietly(reader);
        }

    }

    /**
     * Load cookies. The input is text in the Netscape's 'cookies.txt' file
     * format. Example entry of cookies.txt file:
     * <p>
     * www.archive.org FALSE / FALSE 1311699995 details-visit texts-cralond
     * </p>
     * <p>
     * Each line has 7 tab-separated fields:
     * </p>
     * <ol>
     * <li>DOMAIN: The domain that created and have access to the cookie value.</li>
     * <li>FLAG: A TRUE or FALSE value indicating if hosts within the given
     * domain can access the cookie value.</li>
     * <li>PATH: The path within the domain that the cookie value is valid for.</li>
     * <li>SECURE: A TRUE or FALSE value indicating if to use a secure
     * connection to access the cookie value.</li>
     * <li>EXPIRATION: The expiration time of the cookie value, or -1 for no
     * expiration</li>
     * <li>NAME: The name of the cookie value</li>
     * <li>VALUE: The cookie value</li>
     * </ol>
     * 
     * @param reader
     *            input in the Netscape's 'cookies.txt' format.
     */
    public static Collection<Cookie> readCookies(Reader reader) {
        LinkedList<Cookie> cookies = new LinkedList<Cookie>(); 
        BufferedReader br = new BufferedReader(reader);
        try {
            String line;
            int lineNo = 1;
            while ((line = br.readLine()) != null) {
                if (!line.matches("\\s*(?:#.*)?")) { // skip blank links and comments
                    String[] tokens = line.split("\\t");
                    if (tokens.length == 7) {
                        long epochSeconds = Long.parseLong(tokens[4]);
                        Date expirationDate = (epochSeconds >= 0 ? new Date(epochSeconds * 1000) : null);
                        BasicClientCookie cookie = new BasicClientCookie(tokens[5], tokens[6]);
                        cookie.setDomain(tokens[0]);
                        cookie.setExpiryDate(expirationDate);
                        cookie.setSecure(Boolean.valueOf(tokens[3]).booleanValue());
                        cookie.setPath(tokens[2]);
// XXX httpclient cookie doesn't have this thing?
//                        cookie.setDomainAttributeSpecified(Boolean.valueOf(tokens[1]).booleanValue());
                        logger.fine("Adding cookie: domain " + cookie.getDomain() + " cookie " + cookie);
                        cookies.add(cookie);
                    } else {
                        logger.warning("cookies input line " + lineNo + " invalid, expected 7 tab-delimited tokens");
                    }
                }
                
                lineNo++;
            }
        } catch (IOException e) {
            logger.log(Level.WARNING,e.getMessage(), e);
        }
        return cookies;
    }
    
    public static void saveCookies(String saveCookiesFile, Collection<Cookie> collection) { 
        // Do nothing if cookiesFile is not specified. 
        if (saveCookiesFile == null || saveCookiesFile.length() <= 0) { 
            return; 
        }
      
        FileOutputStream out = null; 
        try { 
            out = new FileOutputStream(new File(saveCookiesFile)); 
            String tab ="\t"; 
            out.write("# Heritrix Cookie File\n".getBytes()); 
            out.write("# This file is the Netscape cookies.txt format\n\n".getBytes()); 
            for (Cookie cookie: collection) { 
                // Guess an initial size 
                MutableString line = new MutableString(1024 * 2); 
                line.append(cookie.getDomain()); 
                line.append(tab);
                // XXX line.append(cookie.isDomainAttributeSpecified() ? "TRUE" : "FALSE"); 
                line.append("TRUE");
                line.append(tab); 
                line.append(cookie.getPath() != null ? cookie.getPath() : "/");
                line.append(tab); 
                line.append(cookie.isSecure() ? "TRUE" : "FALSE"); 
                line.append(tab);
                line.append(cookie.getExpiryDate() != null ? cookie.getExpiryDate().getTime() / 1000 : -1);
                line.append(tab);
                line.append(cookie.getName());
                line.append(tab);                
                line.append(cookie.getValue() != null ? cookie.getValue() : ""); 
                line.append("\n");
                out.write(line.toString().getBytes()); 
            } 
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Unable to write " + saveCookiesFile, e);
        } finally {
            IOUtils.closeQuietly(out);
        } 
    }
    
    /**
     * @see {@link CookieIdentityComparator#compare(Cookie, Cookie)}
     */
    protected String makeKey(Cookie cookie) {
        JSONArray a = new JSONArray();
        a.put(cookie.getName());
        
        String d = cookie.getDomain();
        if (d == null) {
            d = "";
        } else if (d.indexOf('.') == -1) {
            d = d + ".local";
        }
        d = d.toLowerCase(Locale.ENGLISH);
        a.put(d);
        
        String p = cookie.getPath();
        if (p == null) {
            p = "/";
        }
        a.put(p);
        
        return a.toString();
    }

    abstract protected void prepare();
    abstract protected void loadCookies(Reader reader);
    abstract protected void saveCookies(String absolutePath);
}
