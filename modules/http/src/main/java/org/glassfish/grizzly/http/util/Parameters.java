/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2010-2011 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 *
 *
 * This file incorporates work covered by the following copyright and
 * permission notice:
 *
 * Copyright 2004 The Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glassfish.grizzly.http.util;

import java.io.CharConversionException;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.glassfish.grizzly.Buffer;

import org.glassfish.grizzly.Grizzly;

/**
 * @author Costin Manolache
 */
public final class Parameters {
    /**
     * Default Logger.
     */
    private final static Logger logger = Grizzly.logger(Parameters.class);
    // Transition: we'll use the same Hashtable( String->String[] )
    // for the beginning. When we are sure all accesses happen through
    // this class - we can switch to MultiMap
    /* START PWC 6057385
    private Hashtable paramHashStringArray=new Hashtable();
    */
    // START PWC 6057385
    private final LinkedHashMap<String, String[]> paramHashStringArray =
        new LinkedHashMap<String, String[]>();
    // END PWC 6057385
    private boolean didQueryParameters = false;
    private boolean didMerge = false;
    MimeHeaders headers;
    DataChunk queryDC;
//    UDecoder urlDec;
    public static final int INITIAL_SIZE = 4;
    // Garbage-less parameter merging.
    // In a sub-request with parameters, the new parameters
    // will be stored in child. When a getParameter happens,
    // the 2 are merged togheter. The child will be altered
    // to contain the merged values - the parent is allways the
    // original request.
    private Parameters child = null;
    private Parameters parent = null;
    private Parameters currentChild = null;
    Charset encoding = null;
    Charset queryStringEncoding = null;

    public void setQuery(final DataChunk queryBC) {
        this.queryDC = queryBC;
    }

    public void setHeaders(final MimeHeaders headers) {
        this.headers = headers;
    }

    public void setEncoding(final Charset encoding) {
        this.encoding = encoding;
        if (debug > 0) {
            log("Set encoding to " + encoding);
        }
    }

    public void setQueryStringEncoding(final Charset queryStringEncoding) {
        this.queryStringEncoding = queryStringEncoding;
        if (debug > 0) {
            log("Set query string encoding to " + queryStringEncoding);
        }
    }


    public void recycle() {

        paramHashStringArray.clear();
        didQueryParameters = false;
        currentChild = null;
        didMerge = false;
        encoding = null;
    }
    // -------------------- Sub-request support --------------------

    public Parameters getCurrentSet() {
        if (currentChild == null) {
            return this;
        }
        return currentChild;
    }

    /**
     * Create ( or reuse ) a child that will be used during a sub-request. All future changes ( setting query string,
     * adding parameters ) will affect the child ( the parent request is never changed ). Both setters and getters will
     * return the data from the deepest child, merged with data from parents.
     */
    public void push() {
        // We maintain a linked list, that will grow to the size of the
        // longest include chain.
        // The list has 2 points of interest:
        // - request.parameters() is the original request and head,
        // - request.parameters().currentChild() is the current set.
        // The ->child and parent<- links are preserved ( currentChild is not
        // the last in the list )
        // create a new element in the linked list
        // note that we reuse the child, if any - pop will not
        // set child to null !
        if (currentChild == null) {
            currentChild = new Parameters();
//            currentChild.setURLDecoder(urlDec);
            currentChild.parent = this;
            return;
        }
        if (currentChild.child == null) {
            currentChild.child = new Parameters();
//            currentChild.setURLDecoder(urlDec);
            currentChild.child.parent = currentChild;
        } // it is not null if this object already had a child
        // i.e. a deeper include() ( we keep it )
        // the head will be the new element.
        currentChild = currentChild.child;
        currentChild.setEncoding(encoding);
    }

    /**
     * Discard the last child. This happens when we return from a sub-request and the parameters are locally modified.
     */
    public void pop() {
        if (currentChild == null) {
            throw new RuntimeException("Attempt to pop without a push");
        }
        currentChild.recycle();
        currentChild = currentChild.parent;
        // don't remove the top.
    }
    // -------------------- Data access --------------------
    // Access to the current name/values, no side effect ( processing ).
    // You must explicitely call handleQueryParameters and the post methods.
    // This is the original data representation ( hash of String->String[])

    public void addParameterValues(String key, String[] newValues) {
        if (key == null) {
            return;
        }
        String values[];
        if (paramHashStringArray.containsKey(key)) {
            String oldValues[] = paramHashStringArray.get(key);
            values = new String[oldValues.length + newValues.length];
            System.arraycopy(oldValues, 0, values, 0, oldValues.length);
            System.arraycopy(newValues, 0, values, oldValues.length, newValues.length);
        } else {
            values = newValues;
        }
        paramHashStringArray.put(key, values);
    }

    public String[] getParameterValues(String name) {
        handleQueryParameters();
        // sub-request
        if (currentChild != null) {
            currentChild.merge();
            return currentChild.paramHashStringArray.get(name);
        }
        // no "facade"
        return paramHashStringArray.get(name);
    }

    public Set<String> getParameterNames() {
        handleQueryParameters();
        // Slow - the original code
        if (currentChild != null) {
            currentChild.merge();
            /* START PWC 6057385
            return currentChild.paramHashStringArray.keys();
            */
            // START PWC 6057385
            currentChild.paramHashStringArray.keySet();
            // END PWC 6057385
        }
        // merge in child
        /* START PWC 6057385
        return paramHashStringArray.keys();
        */
        // START PWC 6057385
        return paramHashStringArray.keySet();
        // END PWC 6057385
    }

    /**
     * Combine the parameters from parent with our local ones
     */
    private void merge() {
        // recursive
        if (debug > 0) {
            log("Before merging " + this + ' ' + parent + ' ' + didMerge);
            log(paramsAsString());
        }
        // Local parameters first - they take precedence as in spec.
        handleQueryParameters();
        // we already merged with the parent
        if (didMerge) {
            return;
        }
        // we are the top level
        if (parent == null) {
            return;
        }
        // Add the parent props to the child ( lower precedence )
        parent.merge();
        /* START PWC 6057385
        Hashtable parentProps=parent.paramHashStringArray;
        */
        // START PWC 6057385
        LinkedHashMap<String, String[]> parentProps = parent.paramHashStringArray;
        // END PWC 6057385
        merge2(paramHashStringArray, parentProps);
        didMerge = true;
        if (debug > 0) {
            log("After " + paramsAsString());
        }
    }

    // Shortcut.
    public String getParameter(final String name) {
        final String[] values = getParameterValues(name);
        if (values != null) {
            if (values.length == 0) {
                return "";
            }
            return values[0];
        } else {
            return null;
        }
    }
    // -------------------- Processing --------------------

    /**
     * Process the query string into parameters
     */
    public void handleQueryParameters() {
        if (didQueryParameters) {
            return;
        }
        didQueryParameters = true;
        if (queryDC == null || queryDC.isNull()) {
            return;
        }
        if (debug > 0) {
            log("Decoding query " + queryDC + ' ' + queryStringEncoding);
        }
        // TODO obtain the bytes instead of converter to string
        //      then call processParameters(byte[], int, int, String) directly
//        MessageBytes decodedQuery = MessageBytes.newInstance();
//        decodedQuery.setString(queryBC.toString());
        processParameters(queryDC, queryStringEncoding);

    }
    // --------------------

    /**
     * Combine 2 hashtables into a new one. ( two will be added to one ). Used to combine child parameters (
     * RequestDispatcher's query ) with parent parameters ( original query or parent dispatcher )
     */
    /* START PWC 6057385
    private static void merge2(Hashtable one, Hashtable two ) {
        Enumeration e = two.keys();

        while (e.hasMoreElements()) {
            String name = (String) e.nextElement();
    */
    // START PWC 6057385
    private static void merge2(LinkedHashMap<String, String[]> one,
        LinkedHashMap<String, String[]> two) {
        for (final Map.Entry<String,String[]> entry : two.entrySet()) {
            final String name = entry.getKey();
            String[] oneValue = one.get(name);
            String[] twoValue = entry.getValue();
            String[] combinedValue;
            if (twoValue == null) {
            } else {
                if (oneValue == null) {
                    combinedValue = new String[twoValue.length];
                    System.arraycopy(twoValue, 0, combinedValue,
                        0, twoValue.length);
                } else {
                    combinedValue = new String[oneValue.length +
                        twoValue.length];
                    System.arraycopy(oneValue, 0, combinedValue, 0,
                        oneValue.length);
                    System.arraycopy(twoValue, 0, combinedValue,
                        oneValue.length, twoValue.length);
                }
                one.put(name, combinedValue);
            }
        }
    }

    // incredibly inefficient data representation for parameters,
    // until we test the new one
    private void addParam(final String key, final String value) {
        if (key == null) {
            return;
        }
        final String values[];
        if (paramHashStringArray.containsKey(key)) {
            String oldValues[] = paramHashStringArray.get(key);
            values = new String[oldValues.length + 1];
            System.arraycopy(oldValues, 0, values, 0, oldValues.length);
            values[oldValues.length] = value;
        } else {
            values = new String[1];
            values[0] = value;
        }
        paramHashStringArray.put(key, values);
    }

//    public void setURLDecoder(UDecoder u) {
//        urlDec = u;
//    }
    // -------------------- Parameter parsing --------------------
    // This code is not used right now - it's the optimized version
    // of the above.
    // we are called from a single thread - we can do it the hard way
    // if needed
    final BufferChunk tmpName = new BufferChunk();
    final BufferChunk tmpValue = new BufferChunk();
    final CharChunk tmpNameC = new CharChunk(1024);
    final CharChunk tmpValueC = new CharChunk(1024);

    public void processParameters(final Buffer buffer, final int start, final int len) {
        processParameters(buffer, start, len, encoding);
    }

    public void processParameters(final Buffer buffer, final int start, final int len,
        final Charset enc) {
        int end = start + len;
        int pos = start;
        if (debug > 0) {
            log("Bytes: " + buffer.toStringContent(null, start, start + len));
        }
        
        do {
            boolean noEq = false;
            int valStart = -1;
            int valEnd = -1;
            final int nameStart = pos;
            int nameEnd = BufferChunk.indexOf(buffer, nameStart, end, '=');
            // Workaround for a&b&c encoding
            int nameEnd2 = BufferChunk.indexOf(buffer, nameStart, end, '&');
            if ((nameEnd2 != -1) &&
                (nameEnd == -1 || nameEnd > nameEnd2)) {
                nameEnd = nameEnd2;
                noEq = true;
                valStart = nameEnd;
                valEnd = nameEnd;
                if (debug > 0) {
                    log("no equal " + nameStart + ' ' + nameEnd + ' ' +
                            buffer.toStringContent(null, nameStart, nameEnd));
                }
            }
            if (nameEnd == -1) {
                nameEnd = end;
            }
            if (!noEq) {
                valStart = (nameEnd < end) ? nameEnd + 1 : end;
                valEnd = BufferChunk.indexOf(buffer, valStart, end, '&');
                if (valEnd == -1) {
                    valEnd = (valStart < end) ? end : valStart;
                }
            }
            pos = valEnd + 1;
            if (nameEnd <= nameStart) {
                continue;
                // invalid chunk - it's better to ignore
                // XXX log it ?
            }
            tmpName.setBufferChunk(buffer, nameStart, nameEnd);
            tmpValue.setBufferChunk(buffer, valStart, valEnd);

            try {
                addParam(urlDecode(tmpName, enc), urlDecode(tmpValue, enc));
            } catch (IOException e) {
                // Exception during character decoding: skip parameter
            }
            tmpName.recycle();
            tmpValue.recycle();

        } while (pos < end);
    }

    private String urlDecode(final BufferChunk bc, final Charset enc)
        throws IOException {
//        if (urlDec == null) {
//            urlDec = new UDecoder();
//        }
        URLDecoder.decode(bc, true);
        String result;
        if (enc != null) {
            result = bc.toString(enc);
        } else {
            final CharChunk cc = tmpNameC;
            final int length = bc.getLength();
            cc.allocate(length, -1);
            // Default encoding: fast conversion
            final Buffer bbuf = bc.getBuffer();
            final char[] cbuf = cc.getBuffer();
            final int start = bc.getStart();
            for (int i = 0; i < length; i++) {
                cbuf[i] = (char) (bbuf.get(i + start) & 0xff);
            }

            cc.setChars(cbuf, 0, length);
            result = cc.toString();
            cc.recycle();
        }
        return result;
    }

    public void processParameters(char chars[], int start, int len) {
        int end = start + len;
        int pos = start;
        if (debug > 0) {
            log("Chars: " + new String(chars, start, len));
        }
        do {
            boolean noEq = false;
            int nameStart = pos;
            int valStart = -1;
            int valEnd = -1;
            int nameEnd = CharChunk.indexOf(chars, nameStart, end, '=');
            int nameEnd2 = CharChunk.indexOf(chars, nameStart, end, '&');
            if ((nameEnd2 != -1) &&
                (nameEnd == -1 || nameEnd > nameEnd2)) {
                nameEnd = nameEnd2;
                noEq = true;
                valStart = nameEnd;
                valEnd = nameEnd;
                if (debug > 0) {
                    log("no equal " + nameStart + ' ' + nameEnd + ' ' + new String(chars, nameStart,
                        nameEnd - nameStart));
                }
            }
            if (nameEnd == -1) {
                nameEnd = end;
            }
            if (!noEq) {
                valStart = (nameEnd < end) ? nameEnd + 1 : end;
                valEnd = CharChunk.indexOf(chars, valStart, end, '&');
                if (valEnd == -1) {
                    valEnd = (valStart < end) ? end : valStart;
                }
            }
            pos = valEnd + 1;
            if (nameEnd <= nameStart) {
                continue;
                // invalid chunk - no name, it's better to ignore
                // XXX log it ?
            }
            try {
                tmpNameC.append(chars, nameStart, nameEnd - nameStart);
                tmpValueC.append(chars, valStart, valEnd - valStart);
                if (debug > 0) {
                    log(tmpNameC + "= " + tmpValueC);
                }
//                if (urlDec == null) {
//                    urlDec = new UDecoder();
//                }
                URLDecoder.decode(tmpNameC, true);
                URLDecoder.decode(tmpValueC, true);
                if (debug > 0) {
                    log(tmpNameC + "= " + tmpValueC);
                }
                addParam(tmpNameC.toString(), tmpValueC.toString());
            } catch (IOException ex) {
                ex.printStackTrace();
            }
            tmpNameC.recycle();
            tmpValueC.recycle();

        } while (pos < end);
    }

    public void processParameters(final DataChunk data) {
        processParameters(data, encoding);
    }

    public void processParameters(final DataChunk data, final Charset encoding) {
        if (data == null || data.isNull() || data.getLength() <= 0) {
            return;
        }

        try {
            if (data.getType() == DataChunk.Type.Buffer) {
                final BufferChunk bc = data.getBufferChunk();
                processParameters(bc.getBuffer(), bc.getStart(),
                        bc.getLength(), encoding);
            } else {
                if (data.getType() != DataChunk.Type.Chars) {
                    data.toChars(encoding);
                }

                final CharChunk cc = data.getCharChunk();
                processParameters(cc.getChars(), cc.getStart(),
                        cc.getLength());
            }
        } catch (CharConversionException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Debug purpose
     */
    public String paramsAsString() {
        StringBuilder sb = new StringBuilder();
        for (final String s : paramHashStringArray.keySet()) {
            // END PWC 6057385
            sb.append(s).append('=');
            String v[] = paramHashStringArray.get(s);
            for (int i = 0; i < v.length; i++) {
                sb.append(v[i]).append(',');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static final int debug = 0;

    private void log(String s) {
        if (logger.isLoggable(Level.FINEST)) {
            logger.log(Level.FINEST, "Parameters: {0}", s);
        }
    }
    // -------------------- Old code, needs rewrite --------------------

    /**
     * Used by RequestDispatcher
     */
    public void processSingleParameters(final String str) {
        int end = str.length();
        int pos = 0;
        if (debug > 0) {
            log("String: " + str);
        }
        do {
            boolean noEq = false;
            int valStart = -1;
            int valEnd = -1;
            int nameStart = pos;
            int nameEnd = str.indexOf('=', nameStart);
            int nameEnd2 = str.indexOf('&', nameStart);
            if (nameEnd2 == -1) {
                nameEnd2 = end;
            }
            if ((nameEnd2 != -1) &&
                (nameEnd == -1 || nameEnd > nameEnd2)) {
                nameEnd = nameEnd2;
                noEq = true;
                valStart = nameEnd;
                valEnd = nameEnd;
                if (debug > 0) {
                    log("no equal " + nameStart + ' ' + nameEnd + ' ' + str.substring(nameStart, nameEnd));
                }
            }
            if (nameEnd == -1) {
                nameEnd = end;
            }
            if (!noEq) {
                valStart = nameEnd + 1;
                valEnd = str.indexOf('&', valStart);
                if (valEnd == -1) {
                    valEnd = (valStart < end) ? end : valStart;
                }
            }
            pos = valEnd + 1;
            if (nameEnd <= nameStart) {
                continue;
            }
            if (debug > 0) {
                log("XXX " + nameStart + ' ' + nameEnd + ' '
                    + valStart + ' ' + valEnd);
            }
            try {
                tmpNameC.append(str, nameStart, nameEnd - nameStart);
                tmpValueC.append(str, valStart, valEnd - valStart);
                if (debug > 0) {
                    log(tmpNameC + "= " + tmpValueC);
                }
//                if (urlDec == null) {
//                    urlDec = new UDecoder();
//                }
                URLDecoder.decode(tmpNameC, true);
                URLDecoder.decode(tmpValueC, true);
                if (debug > 0) {
                    log(tmpNameC + "= " + tmpValueC);
                }
                if (str.compareTo(tmpNameC.toString()) == 0) {
                    addParam(tmpNameC.toString(), tmpValueC.toString());
                }
            } catch (IOException ex) {
                ex.printStackTrace();
            }
            tmpNameC.recycle();
            tmpValueC.recycle();

        } while (pos < end);
    }

    public void processParameters(String str) {
        int end = str.length();
        int pos = 0;
        if (debug > 0) {
            log("String: " + str);
        }
        do {
            boolean noEq = false;
            int valStart = -1;
            int valEnd = -1;
            int nameStart = pos;
            int nameEnd = str.indexOf('=', nameStart);
            int nameEnd2 = str.indexOf('&', nameStart);
            if (nameEnd2 == -1) {
                nameEnd2 = end;
            }
            if ((nameEnd2 != -1) &&
                (nameEnd == -1 || nameEnd > nameEnd2)) {
                nameEnd = nameEnd2;
                noEq = true;
                valStart = nameEnd;
                valEnd = nameEnd;
                if (debug > 0) {
                    log("no equal " + nameStart + ' ' + nameEnd + ' ' + str.substring(nameStart, nameEnd));
                }
            }
            if (nameEnd == -1) {
                nameEnd = end;
            }
            if (!noEq) {
                valStart = nameEnd + 1;
                valEnd = str.indexOf('&', valStart);
                if (valEnd == -1) {
                    valEnd = (valStart < end) ? end : valStart;
                }
            }
            pos = valEnd + 1;
            if (nameEnd <= nameStart) {
                continue;
            }
            if (debug > 0) {
                log("XXX " + nameStart + ' ' + nameEnd + ' '
                    + valStart + ' ' + valEnd);
            }
            try {
                tmpNameC.append(str, nameStart, nameEnd - nameStart);
                tmpValueC.append(str, valStart, valEnd - valStart);
                if (debug > 0) {
                    log(tmpNameC + "= " + tmpValueC);
                }
//                if (urlDec == null) {
//                    urlDec = new UDecoder();
//                }
                URLDecoder.decode(tmpNameC, true);
                URLDecoder.decode(tmpValueC, true);
                if (debug > 0) {
                    log(tmpNameC + "= " + tmpValueC);
                }
                addParam(tmpNameC.toString(), tmpValueC.toString());
            } catch (IOException ex) {
                ex.printStackTrace();
            }
            tmpNameC.recycle();
            tmpValueC.recycle();

        } while (pos < end);
    }

}