/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.uima.cas.impl;

import java.util.NoSuchElementException;

import org.apache.uima.cas.FeatureStructure;
import org.apache.uima.cas.Type;
import org.apache.uima.cas.admin.FSIndexComparator;
import org.apache.uima.internal.util.ComparableIntPointerIterator;
import org.apache.uima.internal.util.IntComparator;
import org.apache.uima.internal.util.IntPointerIterator;
import org.apache.uima.internal.util.IntVector;

public class FSIntArrayIndex<T extends FeatureStructure> extends FSLeafIndexImpl<T> {

  private class IntVectorIterator implements ComparableIntPointerIterator, LowLevelIterator {

    private int itPos;

    private IntComparator comp;

    private int modificationSnapshot; // to catch illegal modifications

    private int[] detectIllegalIndexUpdates; // shared copy with Index Repository

    private int typeCode;

    public boolean isConcurrentModification() {
      return modificationSnapshot != detectIllegalIndexUpdates[typeCode];
    }

    public void resetConcurrentModification() {
      modificationSnapshot = detectIllegalIndexUpdates[typeCode];
    }

    private IntVectorIterator() {
      super();
      this.itPos = 0;
    }

    private IntVectorIterator(IntComparator comp) {
      this();
      this.comp = comp;
    }

    public boolean isValid() {
      return ((this.itPos >= 0) && (this.itPos < FSIntArrayIndex.this.index.size()));
    }

    public void moveToFirst() {
      this.itPos = 0;
    }

    public void moveToLast() {
      this.itPos = FSIntArrayIndex.this.index.size() - 1;
    }

    public void moveToNext() {
      ++this.itPos;
    }

    public void moveToPrevious() {
      --this.itPos;
    }

    public int ll_get() {
      if (!isValid()) {
        throw new NoSuchElementException();
      }
      return FSIntArrayIndex.this.index.get(this.itPos);
    }

    /**
     * @see org.apache.uima.internal.util.IntPointerIterator#copy()
     */
    public Object copy() {
      IntVectorIterator copy = new IntVectorIterator(this.comp);
      copy.itPos = this.itPos;
      return copy;
    }

    /**
     * @see java.lang.Comparable#compareTo(Object)
     */
    public int compareTo(Object o) throws NoSuchElementException {
      return this.comp.compare(get(), ((IntVectorIterator) o).get());
    }

    /**
     * @see org.apache.uima.internal.util.IntPointerIterator#moveTo(int)
     */
    public void moveTo(int i) {
      final int position = find(i);
      if (position >= 0) {
        this.itPos = position;
      } else {
        this.itPos = -(position + 1);
      }
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.uima.cas.impl.LowLevelIterator#ll_get()
     */
    public int get() throws NoSuchElementException {
      return ll_get();
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.uima.cas.impl.LowLevelIterator#moveToNext()
     */
    public void inc() {
      moveToNext();
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.uima.cas.impl.LowLevelIterator#moveToPrevious()
     */
    public void dec() {
      moveToPrevious();
    }

    public int ll_indexSize() {
      return FSIntArrayIndex.this.size();
    }

    public LowLevelIndex ll_getIndex() {
      return FSIntArrayIndex.this;
    }

  }

  // The index, a vector of FS references.
  private IntVector index;
  
  final private int initialSize;

  FSIntArrayIndex(CASImpl cas, Type type, int initialSize, int indexType) {
    super(cas, type, indexType);
    this.initialSize = initialSize;
    this.index = new IntVector(initialSize);
  }

  /**
   * @see org.apache.uima.cas.impl.FSLeafIndexImpl#init(org.apache.uima.cas.admin.FSIndexComparator)
   */
  boolean init(FSIndexComparator comp) {
    boolean rc = super.init(comp);
    return rc;
  }

  IntVector getVector() {
    return this.index;
  }

  public void flush() {
    // do this way to reset size if it grew
    if (this.index.size() > this.initialSize) {
      this.index = new IntVector(initialSize);
    } else {
      this.index.removeAllElements();
    }
  }

  // public final boolean insert(int fs) {
  // this.index.add(fs);
  // return true;
  // }

  public final boolean insert(int fs) {
    // First, check if we can insert at the end.
    final int[] indexArray = this.index.getArray();
    final int length = this.index.size();
    if (length == 0) {
      this.index.add(fs);
      return true;
    }
    final int last = indexArray[length - 1];
    if (compare(last, fs) < 0) {
      this.index.add(fs);
      return true;
    }
    final int pos = this.binarySearch(indexArray, fs, 0, length);
    if (pos >= 0) {
      this.index.add(pos + 1, fs);
    } else {
      this.index.add(-(pos + 1), fs);
    }
    return true;
  }

  // public IntIteratorStl iterator() {
  // return new IntVectorIterator();
  // }

  private final int find(int ele) {
    // return this.index.indexOf(ele);
    return binarySearch(this.index.getArray(), ele, 0, this.index.size());
  }

  // private final int find(int ele)
  // {
  // final int[] array = this.index.getArray();
  // final int max = this.index.size();
  // for (int i = 0; i < max; i++)
  // {
  // if (compare(ele, array[i]) == 0)
  // {
  // return i;
  // }
  // }
  // return -1;
  // }

  // public int compare(int fs1, int fs2)
  // {
  // if (fs1 < fs2)
  // {
  // return -1;
  // } else if (fs1 > fs2)
  // {
  // return 1;
  // } else
  // {
  // return 0;
  // }
  // }

  // Do binary search on index.
  // return negative number of insertion point if not found
  private final int binarySearch(int[] array, int ele, int start, int end) {
    --end; // Make end a legal value.
    int i; // Current position
    int comp; // Compare value
    while (start <= end) {
      i = (int)(((long)start + end) / 2);
      comp = compare(ele, array[i]);
      if (comp == 0) {
        return i;
      }
      if (start == end) {
        if (comp < 0) {
          return (-i) - 1;
        }
        // comp > 0
        return (-i) - 2; // (-(i+1))-1
      }
      if (comp < 0) {
        end = i - 1;
      } else { // comp > 0
        start = i + 1;
      }
    }
    // This means that the input span is empty.
    return (-start) - 1;
  }

  public ComparableIntPointerIterator pointerIterator(IntComparator comp,
          int[] detectIllegalIndexUpdates, int typeCode) {
    IntVectorIterator ivi = new IntVectorIterator(comp);
    ivi.modificationSnapshot = detectIllegalIndexUpdates[typeCode];
    ivi.detectIllegalIndexUpdates = detectIllegalIndexUpdates;
    ivi.typeCode = typeCode;
    return ivi;
  }

  /**
   * @see org.apache.uima.cas.impl.FSLeafIndexImpl#refIterator()
   */
  protected IntPointerIterator refIterator() {
    return new IntVectorIterator();
  }

  /*
   * (non-Javadoc)
   * 
   * @see org.apache.uima.cas.impl.LowLevelIndex#ll_iterator()
   */
  public LowLevelIterator ll_iterator() {
    return new IntVectorIterator();
  }

  /**
   * @see org.apache.uima.cas.impl.FSLeafIndexImpl#refIterator(int)
   */
  protected IntPointerIterator refIterator(int fsCode) {
    IntVectorIterator it = new IntVectorIterator();
    final int pos = find(fsCode);
    if (pos >= 0) {
      it.itPos = pos;
    } else {
      it.itPos = -(pos + 1);
    }
    return it;
  }

  /**
   * @see org.apache.uima.cas.FSIndex#contains(FeatureStructure)
   */
  public boolean contains(FeatureStructure fs) {
    return (find(((FeatureStructureImpl) fs).getAddress()) >= 0);
  }

  public FeatureStructure find(FeatureStructure fs) {
    // Cast to implementation.
    FeatureStructureImpl fsi = (FeatureStructureImpl) fs;
    // Use internal find method.
    final int resultAddr = find(fsi.getAddress());
    // If found, create new FS to return.
    if (resultAddr >= 0) {
      return fsi.getCASImpl().createFS(this.index.get(resultAddr));
    }
    // Not found.
    return null;
  }

  /**
   * @see org.apache.uima.cas.FSIndex#size()
   */
  public int size() {
    return this.index.size();
  }

  /**
   * @see org.apache.uima.cas.impl.FSLeafIndexImpl#deleteFS(org.apache.uima.cas.FeatureStructure)
   */
  public void deleteFS(FeatureStructure fs) {
    final int addr = ((FeatureStructureImpl) fs).getAddress();
    remove(addr);
//    final int pos = this.index.indexOf(addr);
//    if (pos >= 0) {
//      this.index.remove(pos);
//    }
  }

  
  /*
   * Some day we may want to remove all occurrances of this feature structure, not just the
   * first one we come to (in the case where the exact identical FS has been added to the 
   * index multiple times).  The issues around this are:
   *   multiple adds are lost on serialization/ deserialization
   *   it take time to remove all instances - especially from bag indexes
   */
  
  /**
   * This code is written to remove (if it exists)
   * the exact FS, not just one which matches in the sort comparator.
   * 
   *   
   */
  public void remove(int fsRef) {
    final int pos = find(fsRef);  // finds "same" element per compare key
    if (pos < 0) {
      return;  // not in index
    }
    if (this.index.get(pos) == fsRef) {
      this.index.remove(pos);
      return;
    }
    // search down and up for == fsRef, while key values ==
    for (int movingPos = pos - 1; movingPos >= 0; movingPos --) { 
      if (compare(this.index.get(movingPos), fsRef) != 0) {
        break;  // not found
      }
      if (this.index.get(movingPos) == fsRef) {
        this.index.remove(movingPos);
        return;
      }
    }
    // search up
    for (int movingPos = pos + 1; movingPos < this.index.size(); movingPos ++) {
      if (compare(this.index.get(movingPos), fsRef) != 0) {
        return;  // not found
      }
      if (this.index.get(movingPos) == fsRef) {
        this.index.remove(movingPos);
        return;
      }
    }
    
//    final int pos = this.index.indexOf(fsRef);
//    if (pos >= 0) {
//      this.index.remove(pos);
//    }
  }

}