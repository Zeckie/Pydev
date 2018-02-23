/**
 * Copyright (c) 2005-2013 by Appcelerator, Inc. All Rights Reserved.
 * Licensed under the terms of the Eclipse Public License (EPL).
 * Please see the license.txt included with this distribution for details.
 * Any modifications to this file must keep this entire header intact.
 */
package com.python.pydev.analysis.additionalinfo;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;

import org.python.pydev.ast.codecompletion.revisited.PyPublicTreeMap;
import org.python.pydev.core.FastBufferedReader;
import org.python.pydev.core.IInfo;
import org.python.pydev.core.IPythonNature;
import org.python.pydev.core.ObjectsInternPool;
import org.python.pydev.core.ObjectsInternPool.ObjectsPoolMap;
import org.python.pydev.core.log.Log;
import org.python.pydev.shared_core.string.FastStringBuffer;
import org.python.pydev.shared_core.string.StringUtils;

/**
 * @author Fabio
 *
 */
public class TreeIO {

    private static final char END_IINFO_SEPARATOR = '@';
    private static final char NAME_CHAR_SEPARATOR = '!';
    private static final char PATH_CHAR_SEPARATOR = '&';
    private static final char FILE_CHAR_SEPARATOR = '^';
    private static final char LINE_CHAR_SEPARATOR = '#';
    private static final char COL_CHAR_SEPARATOR = '*';

    /**
     * Tree is written as:
     * line 1= tree size
     * cub|2|CubeColourDialog!13&999@CUBIC!263@cube!202&999@
     */
    public static void dumpTreeToBuffer(SortedMap<String, Set<IInfo>> tree, FastStringBuffer tempBuf,
            Map<String, Integer> strToInt) {
        Set<Entry<String, Set<IInfo>>> entrySet = tree.entrySet();
        Iterator<Entry<String, Set<IInfo>>> it = entrySet.iterator();
        tempBuf.append(entrySet.size());
        tempBuf.append('\n');
        while (it.hasNext()) {
            Entry<String, Set<IInfo>> next = it.next();
            tempBuf.append(next.getKey());
            Set<IInfo> value = next.getValue();

            tempBuf.append('|');
            tempBuf.append(value.size());
            tempBuf.append('|');

            Iterator<IInfo> it2 = value.iterator();
            Integer integer;
            while (it2.hasNext()) {
                IInfo info = it2.next();
                tempBuf.append(info.getName());
                tempBuf.append(NAME_CHAR_SEPARATOR);

                String path = info.getPath();
                if (path != null) {
                    integer = strToInt.get(path);
                    if (integer == null) {
                        integer = strToInt.size() + 1;
                        strToInt.put(path, integer);
                    }
                    tempBuf.append(integer);
                    tempBuf.append(PATH_CHAR_SEPARATOR);
                }

                String file = info.getFile();
                if (file != null) {
                    integer = strToInt.get(file);
                    if (integer == null) {
                        integer = strToInt.size() + 1;
                        strToInt.put(file, integer);
                    }
                    tempBuf.append(integer);
                    tempBuf.append(FILE_CHAR_SEPARATOR);
                }
                tempBuf.append(info.getLine());
                tempBuf.append(LINE_CHAR_SEPARATOR);

                tempBuf.append(info.getCol());
                tempBuf.append(COL_CHAR_SEPARATOR);

                String modName = info.getDeclaringModuleName();

                integer = strToInt.get(modName);
                if (integer == null) {
                    integer = strToInt.size() + 1;
                    strToInt.put(modName, integer);
                }

                int v = integer << 3;
                v |= info.getType();
                tempBuf.append(v); //Write a single for name+type

                tempBuf.append('@');
            }
            tempBuf.append('\n');
        }
        tempBuf.append("-- END TREE\n");
    }

    /**
     * Dict format is the following:
     * -- START DICTIONARY
     * dictionary size
     * name=integer
     */
    public static void dumpDictToBuffer(Map<String, Integer> strToInt, FastStringBuffer buf2) {
        Iterator<Entry<String, Integer>> it = strToInt.entrySet().iterator();

        buf2.append("-- START DICTIONARY\n");
        buf2.append(strToInt.size());
        buf2.append('\n');
        while (it.hasNext()) {
            Entry<String, Integer> next = it.next();
            buf2.append(next.getValue());
            buf2.append('=');
            buf2.append(next.getKey());
            buf2.append('\n');
        }
        buf2.append("-- END DICTIONARY\n");
    }

    /**
     * @author Fabio
     *
     */
    private static final class MapEntry implements Map.Entry {

        private final String key;
        private final HashSet<IInfo> set;

        public MapEntry(String key, HashSet<IInfo> set) {
            this.key = key;
            this.set = set;
        }

        @Override
        public Object getKey() {
            return key;
        }

        @Override
        public Object getValue() {
            return set;
        }

        @Override
        public Object setValue(Object value) {
            throw new UnsupportedOperationException();
        }
    }

    public static PyPublicTreeMap<String, Set<IInfo>> loadTreeFrom(final FastBufferedReader reader,
            final Map<Integer, String> dictionary, FastStringBuffer buf, ObjectsPoolMap objectsPoolMap,
            IPythonNature nature)
            throws IOException {
        PyPublicTreeMap<String, Set<IInfo>> tree = new PyPublicTreeMap<String, Set<IInfo>>();
        final int size = StringUtils.parsePositiveInt(reader.readLine());

        try {

            final Entry[] entries = new Entry[size];
            //each line is something as: cub|CubeColourDialog!13&999@CUBIC!263@cube!202&999@
            //note: the path (2nd int in record) is optional
            for (int iEntry = 0; iEntry < size; iEntry++) {
                buf.clear();
                FastStringBuffer readLine = reader.readLine();
                if (readLine == null || readLine.startsWith("-- ")) {
                    throw new RuntimeException("Unexpected line: " + readLine);
                }
                char[] internalCharsArray = readLine.getInternalCharsArray();
                int length = readLine.length();
                String key = null;
                String infoName = null;
                String path = null;
                String file = null;
                int line = 0;
                int col = 0;

                int i = 0;

                OUT: for (; i < length; i++) {
                    char c = internalCharsArray[i];
                    switch (c) {
                        case '|':
                            key = ObjectsInternPool.internLocal(objectsPoolMap, buf.toString());
                            buf.clear();
                            i++;
                            break OUT;
                        default:
                            buf.appendResizeOnExc(c);
                    }
                }

                int hashSize = 0;
                OUT2: for (; i < length; i++) {
                    char c = internalCharsArray[i];
                    switch (c) {
                        case '|':
                            hashSize = StringUtils.parsePositiveInt(buf);
                            buf.clear();
                            i++;
                            break OUT2;
                        default:
                            buf.appendResizeOnExc(c);
                    }
                }
                HashSet<IInfo> set = new HashSet<IInfo>(hashSize);

                for (; i < length; i++) {
                    char c = internalCharsArray[i];
                    switch (c) {
                        case NAME_CHAR_SEPARATOR:
                            infoName = ObjectsInternPool.internLocal(objectsPoolMap, buf.toString());
                            buf.clear();
                            break;

                        case PATH_CHAR_SEPARATOR:
                            path = dictionary.get(StringUtils.parsePositiveInt(buf));
                            buf.clear();
                            break;

                        case FILE_CHAR_SEPARATOR:
                            file = dictionary.get(StringUtils.parsePositiveInt(buf));
                            buf.clear();
                            break;

                        case LINE_CHAR_SEPARATOR:
                            line = StringUtils.parsePositiveInt(buf);
                            buf.clear();
                            break;

                        case COL_CHAR_SEPARATOR:
                            col = StringUtils.parsePositiveInt(buf);
                            buf.clear();
                            break;

                        case END_IINFO_SEPARATOR:
                            int dictKey = StringUtils.parsePositiveInt(buf);
                            byte type = (byte) dictKey;
                            type &= 0x07; //leave only the 3 least significant bits there (this is the type -- value from 0 - 8).

                            dictKey = (dictKey >> 3); // the entry in the dict doesn't have the least significant bits there.
                            buf.clear();
                            String moduleDeclared = dictionary.get(dictKey);
                            if (moduleDeclared == null) {
                                throw new AssertionError("Unable to find key: " + dictKey);
                            }
                            if (infoName == null) {
                                throw new AssertionError("Info name may not be null. Line: " + line);
                            }
                            switch (type) {
                                case IInfo.CLASS_WITH_IMPORT_TYPE:
                                    set.add(new ClassInfo(infoName, moduleDeclared, path, false, nature, file, line,
                                            col));
                                    break;
                                case IInfo.METHOD_WITH_IMPORT_TYPE:
                                    set.add(new FuncInfo(infoName, moduleDeclared, path, false, nature, file, line,
                                            col));
                                    break;
                                case IInfo.ATTRIBUTE_WITH_IMPORT_TYPE:
                                    set.add(new AttrInfo(infoName, moduleDeclared, path, false, nature, file, line,
                                            col));
                                    break;
                                case IInfo.NAME_WITH_IMPORT_TYPE:
                                    set.add(new NameInfo(infoName, moduleDeclared, path, false, nature, file, line,
                                            col));
                                    break;
                                case IInfo.MOD_IMPORT_TYPE:
                                    set.add(new ModInfo(infoName, false, nature, file, line, col));
                                    break;
                                default:
                                    Log.log("Unexpected type: " + type);
                            }
                            file = null;
                            break;
                        default:
                            buf.appendResizeOnExc(c);
                    }
                }

                entries[iEntry] = new MapEntry(key, set);
            }

            tree.buildFromSorted(size, new Iterator() {
                private int iNext;

                @Override
                public boolean hasNext() {
                    return iNext > size;
                }

                @Override
                public Object next() {
                    Object o = entries[iNext];
                    iNext++;
                    return o;
                }

                @Override
                public void remove() {
                }

            }, null, null);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
        return tree;
    }

    public static Map<Integer, String> loadDictFrom(FastBufferedReader reader, FastStringBuffer buf,
            ObjectsPoolMap objectsPoolMap) throws IOException {
        int size = StringUtils.parsePositiveInt(reader.readLine());
        HashMap<Integer, String> map = new HashMap<Integer, String>(size + 5);

        FastStringBuffer line;
        int val = 0;
        while (true) {
            line = reader.readLine();
            if (line == null) {
                return map;

            } else if (line.startsWith("-- ")) {
                if (line.startsWith("-- END DICTIONARY")) {
                    return map;
                }
                throw new RuntimeException("Unexpected line: " + line);

            } else {
                int length = line.length();
                //line is str=int
                for (int i = 0; i < length; i++) {
                    char c = line.charAt(i);
                    if (c == '=') {
                        val = StringUtils.parsePositiveInt(buf);
                        buf.clear();
                    } else {
                        buf.appendResizeOnExc(c);
                    }
                }
                String bufStr = buf.toString();
                String interned = objectsPoolMap.get(bufStr);
                if (interned == null) {
                    interned = bufStr;
                    objectsPoolMap.put(bufStr, bufStr);
                }
                map.put(val, interned);
                buf.clear();
            }
        }
    }
}
