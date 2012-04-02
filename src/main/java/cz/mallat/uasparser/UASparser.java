package cz.mallat.uasparser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jregex.Matcher;
import jregex.Pattern;
import cz.mallat.uasparser.fileparser.Entry;
import cz.mallat.uasparser.fileparser.PHPFileParser;
import cz.mallat.uasparser.fileparser.Section;

/**
 * User agent parser.
 *
 * @author oli
 *
 */
public class UASparser {

    static final String INFO_URL = "http://user-agent-string.info";

    protected Map<String, RobotEntry> robotsMap;
    protected Map<Long, OsEntry> osMap;
    protected Map<Long, BrowserEntry> browserMap;
    protected Map<Long, String> browserTypeMap;
    protected Map<String, Long> browserRegMap;
    protected Map<Long, Long> browserOsMap;
    protected Map<String, Long> osRegMap;

    protected Map<Pattern, Long> compiledBrowserRegMap;
    protected Map<Pattern, Long> compiledOsRegMap;

    protected UserAgentInfo unknownAgentInfo;

    /**
     * Use the given filename to load the definition file from the local filesystem
     *
     * @param localDefinitionFilename
     * @throws IOException
     */
    public UASparser(String localDefinitionFilename) throws IOException {
        loadDataFromFile(new File(localDefinitionFilename));
        unknownAgentInfo = new UserAgentInfo();
    }

    /**
     * Use the given inputstream to load the definition file from the local filesystem
     *
     * @param inputStreamToDefinitionFile
     * @throws IOException
     */
    public UASparser(InputStream inputStreamToDefinitionFile) throws IOException {
        loadDataFromFile(inputStreamToDefinitionFile);
        unknownAgentInfo = new UserAgentInfo();
    }

    /**
     * Constructor for inherented classes
     */
    protected UASparser() {
        // empty
    }

    /**
     * When a class inherents from this class, it probably has to override this method
     */
    protected void checkDataMaps() throws IOException {
        // empty for this base class
    }

    /**
     * Parse the given user agent string and returns a UserAgentInfo object with the related data
     *
     * @param useragent
     * @throws IOException
     *             may happen when the retrieval of the data file fails
     * @return
     */
    public UserAgentInfo parse(String useragent) throws IOException {
        UserAgentInfo retObj = new UserAgentInfo();

        if (useragent == null) {
            return retObj;
        }
        useragent = useragent.trim();

        // check that the data maps are up-to-date
        checkDataMaps();

        // first check if it's a robot
        if (!processRobot(useragent, retObj)) {
            // search for a browser on the browser regex patterns
            boolean osFound = processBrowserRegex(useragent, retObj);

            if (!osFound) {
                // search the OS regex patterns for the used OS
                processOsRegex(useragent, retObj);
            }
        }
        return retObj;
    }

    /**
     * Parse the given user agent string and returns a UserAgentInfo object
     * with only the related Browser data set.
     *
     * @param useragent
     * @return {@link UserAgentInfo}
     */
    public UserAgentInfo parseBrowserOnly(String useragent) {

        if (useragent == null) {
            return unknownAgentInfo;
        }

        UserAgentInfo retObj = new UserAgentInfo();
        processBrowserRegex(useragent, retObj);
        return retObj;
    }

    /**
     * Precompile all regular regexes
     */
    protected void preCompileRegExes() {
        preCompileBrowserRegMap();
        preCompileOsRegMap();
    }

    /**
     * Precompile browser regexes
     */
    protected void preCompileBrowserRegMap() {
        compiledBrowserRegMap = new LinkedHashMap<Pattern, Long>(browserRegMap.size());
        for (Map.Entry<String, Long> entry : browserRegMap.entrySet()) {
            Pattern pattern = new Pattern(entry.getKey(), Pattern.IGNORE_CASE | Pattern.DOTALL);
            compiledBrowserRegMap.put(pattern, entry.getValue());
        }
    }

    /**
     * Precompile OS regexes
     */
    protected void preCompileOsRegMap() {
        compiledOsRegMap = new LinkedHashMap<Pattern, Long>(osRegMap.size());
        for (Map.Entry<String, Long> entry : osRegMap.entrySet()) {
            Pattern pattern = new Pattern(entry.getKey(), Pattern.IGNORE_CASE | Pattern.DOTALL);
            compiledOsRegMap.put(pattern, entry.getValue());
        }
    }

    /**
     * Searches in the os regex table. if found a match copies the os data
     *
     * @param useragent
     * @param retObj
     */
    private void processOsRegex(String useragent, UserAgentInfo retObj) {
        for (Map.Entry<Pattern, Long> entry : compiledOsRegMap.entrySet()) {
            Matcher matcher = entry.getKey().matcher(useragent);
            if (matcher.find()) {
                // simply copy the OS data into the result object
                Long idOs = entry.getValue();
                OsEntry os = osMap.get(idOs);
                if (os != null) {
                    os.copyTo(retObj);
                }
                break;
            }
        }
    }

    /**
     * Searchs in the browser regex table. if found a match copies the browser data and if possible os data
     *
     * @param useragent
     * @param retObj
     * @return
     */
    private boolean processBrowserRegex(String useragent, UserAgentInfo retObj) {
        boolean osFound = false;
        for (Map.Entry<Pattern, Long> entry : compiledBrowserRegMap.entrySet()) {
            Matcher matcher = entry.getKey().matcher(useragent);
            if (matcher.find()) {
                Long idBrowser = entry.getValue();
                BrowserEntry be = browserMap.get(idBrowser);
                if (be != null) {
                    copyType(retObj, be);
                    String browserVersionInfo = null;
                    if (matcher.groupCount() > 0) {
                        browserVersionInfo = matcher.group(1);
                    }
                    // copy the browser data into the result
                    be.copyTo(retObj, browserVersionInfo);
                }
                // check if this browser has exactly one OS mapped
                Long idOs = browserOsMap.get(idBrowser);
                if (idOs != null) {
                    osFound = true;
                    OsEntry os = osMap.get(idOs);
                    if (os != null) {
                        os.copyTo(retObj);
                    }
                }
                break;
            }
        }
        return osFound;
    }

    /**
     * Sets the source type, if possible
     *
     * @param retObj
     * @param be
     */
    private void copyType(UserAgentInfo retObj, BrowserEntry be) {
        Long type = be.getType();
        if (type == null) {
            return;
        }
        String typeString = browserTypeMap.get(type);
        if (typeString == null) {
            return;
        }
        retObj.setTyp(typeString);
    }

    /**
     * Checks if the useragent comes from a robot. if yes copies all the data to the result object
     *
     * @param useragent
     * @param retObj
     * @return true if the useragent belongs to a robot, else false
     */
    private boolean processRobot(String useragent, UserAgentInfo retObj) {
        String lcUserAgent = useragent.toLowerCase();
        if (robotsMap.containsKey(lcUserAgent)) {
            retObj.setTyp("Robot");
            RobotEntry robotEntry = robotsMap.get(lcUserAgent);
            robotEntry.copyTo(retObj);
            if (robotEntry.getOsId() != null) {
                OsEntry os = osMap.get(robotEntry.getOsId());
                if (os != null) {
                    os.copyTo(retObj);
                }
            }
            return true;
        }
        return false;
    }

    /**
     * loads the data file and creates all internal data structs
     *
     * @param definitionFile
     * @throws IOException
     */
    protected void loadDataFromFile(File definitionFile) throws IOException {
        PHPFileParser fp = new PHPFileParser(definitionFile);
        createInternalDataStructre(fp.getSections());
    }

    /**
     * loads the data file and creates all internal data structs
     *
     * @param is
     * @throws IOException
     */
    protected void loadDataFromFile(InputStream is) throws IOException {
        PHPFileParser fp = new PHPFileParser(is);
        createInternalDataStructre(fp.getSections());
    }

    /**
     * Creates the internal data structes from the seciontList
     *
     * @param sectionList
     */
    protected void createInternalDataStructre(List<Section> sectionList) {
        for (Section sec : sectionList) {
            if ("robots".equals(sec.getName())) {
                Map<String, RobotEntry> robotsMapTmp = new HashMap<String, RobotEntry>();
                for (Entry en : sec.getEntries()) {
                    RobotEntry re = new RobotEntry(en.getData());
                    robotsMapTmp.put(re.getUserAgentString(), re);
                }
                robotsMap = robotsMapTmp;
            } else if ("os".equals(sec.getName())) {
                Map<Long, OsEntry> osMapTmp = new HashMap<Long, OsEntry>();
                for (Entry en : sec.getEntries()) {
                    OsEntry oe = new OsEntry(en.getData());
                    osMapTmp.put(Long.parseLong(en.getKey()), oe);
                }
                osMap = osMapTmp;
            } else if ("browser".equals(sec.getName())) {
                Map<Long, BrowserEntry> browserMapTmp = new HashMap<Long, BrowserEntry>();
                for (Entry en : sec.getEntries()) {
                    BrowserEntry be = new BrowserEntry(en.getData());
                    browserMapTmp.put(Long.parseLong(en.getKey()), be);
                }
                browserMap = browserMapTmp;
            } else if ("browser_type".equals(sec.getName())) {
                Map<Long, String> browserTypeMapTmp = new HashMap<Long, String>();
                for (Entry en : sec.getEntries()) {
                    browserTypeMapTmp.put(Long.parseLong(en.getKey()), en.getData().iterator().next());
                }
                browserTypeMap = browserTypeMapTmp;
            } else if ("browser_reg".equals(sec.getName())) {
                Map<String, Long> browserRegMapTmp = new LinkedHashMap<String, Long>();
                for (Entry en : sec.getEntries()) {
                    Iterator<String> it = en.getData().iterator();
                    browserRegMapTmp.put(convertPerlToJavaRegex(it.next()), Long.parseLong(it.next()));
                }
                browserRegMap = browserRegMapTmp;
            } else if ("browser_os".equals(sec.getName())) {
                Map<Long, Long> browserOsMapTmp = new HashMap<Long, Long>();
                for (Entry en : sec.getEntries()) {
                    browserOsMapTmp.put(Long.parseLong(en.getKey()), Long.parseLong(en.getData().iterator().next()));
                }
                browserOsMap = browserOsMapTmp;
            } else if ("os_reg".equals(sec.getName())) {
                Map<String, Long> osRegMapTmp = new LinkedHashMap<String, Long>();
                for (Entry en : sec.getEntries()) {
                    Iterator<String> it = en.getData().iterator();
                    osRegMapTmp.put(convertPerlToJavaRegex(it.next()), Long.parseLong(it.next()));
                }
                osRegMap = osRegMapTmp;
            }
        }
        preCompileRegExes();
    }

    /**
     * Converts a PERL style regex into the Java style. That means in removes the leading and the last / and removes the modifiers
     *
     * @param regex
     * @return
     */
    private String convertPerlToJavaRegex(String regex) {
        regex = regex.substring(1);
        int lastIndex = regex.lastIndexOf('/');
        regex = regex.substring(0, lastIndex);
        return regex;
    }

}
