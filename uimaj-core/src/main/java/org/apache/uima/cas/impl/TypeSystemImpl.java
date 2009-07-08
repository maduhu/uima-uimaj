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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Vector;

import org.apache.uima.cas.CAS;
import org.apache.uima.cas.Feature;
import org.apache.uima.cas.Type;
import org.apache.uima.cas.TypeNameSpace;
import org.apache.uima.cas.TypeSystem;
import org.apache.uima.cas.admin.CASAdminException;
import org.apache.uima.cas.admin.TypeSystemMgr;
import org.apache.uima.internal.util.IntVector;
import org.apache.uima.internal.util.StringToIntMap;
import org.apache.uima.internal.util.SymbolTable;
import org.apache.uima.internal.util.rb_trees.IntRedBlackTree;
import org.apache.uima.internal.util.rb_trees.RedBlackTree;

/**
 * Type system implementation.
 * 
 */
public class TypeSystemImpl implements TypeSystemMgr, LowLevelTypeSystem {

  private static class ListIterator implements Iterator {

    private final List list;

    private final int len;

    private int pos = 0;

    private ListIterator(List list, int max) {
      super();
      this.list = list;
      this.len = (max < list.size()) ? max : list.size();
    }

    public boolean hasNext() {
      return this.pos < this.len;
    }

    public Object next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      Object o = this.list.get(this.pos);
      ++this.pos;
      return o;
    }

    public void remove() {
      throw new UnsupportedOperationException();
    }

  }

  // static maps ok for now - only built-in mappings stored here
  // which are the same for all type system instances
  private static HashMap arrayComponentTypeNameMap = new HashMap();

  private static HashMap arrayTypeComponentNameMap = new HashMap();

  private static final String arrayTypeSuffix = "[]";

  static {
    arrayComponentTypeNameMap.put(CAS.TYPE_NAME_TOP, CAS.TYPE_NAME_FS_ARRAY);
    arrayComponentTypeNameMap.put(CAS.TYPE_NAME_BOOLEAN, CAS.TYPE_NAME_BOOLEAN_ARRAY);
    arrayComponentTypeNameMap.put(CAS.TYPE_NAME_BYTE, CAS.TYPE_NAME_BYTE_ARRAY);
    arrayComponentTypeNameMap.put(CAS.TYPE_NAME_SHORT, CAS.TYPE_NAME_SHORT_ARRAY);
    arrayComponentTypeNameMap.put(CAS.TYPE_NAME_INTEGER, CAS.TYPE_NAME_INTEGER_ARRAY);
    arrayComponentTypeNameMap.put(CAS.TYPE_NAME_FLOAT, CAS.TYPE_NAME_FLOAT_ARRAY);
    arrayComponentTypeNameMap.put(CAS.TYPE_NAME_LONG, CAS.TYPE_NAME_LONG_ARRAY);
    arrayComponentTypeNameMap.put(CAS.TYPE_NAME_DOUBLE, CAS.TYPE_NAME_DOUBLE_ARRAY);
    arrayComponentTypeNameMap.put(CAS.TYPE_NAME_STRING, CAS.TYPE_NAME_STRING_ARRAY);
  }

  static {
    arrayTypeComponentNameMap.put(CAS.TYPE_NAME_FS_ARRAY, CAS.TYPE_NAME_TOP);
    arrayTypeComponentNameMap.put(CAS.TYPE_NAME_BOOLEAN_ARRAY, CAS.TYPE_NAME_BOOLEAN);
    arrayTypeComponentNameMap.put(CAS.TYPE_NAME_BYTE_ARRAY, CAS.TYPE_NAME_BYTE);
    arrayTypeComponentNameMap.put(CAS.TYPE_NAME_SHORT_ARRAY, CAS.TYPE_NAME_SHORT);
    arrayTypeComponentNameMap.put(CAS.TYPE_NAME_INTEGER_ARRAY, CAS.TYPE_NAME_INTEGER);
    arrayTypeComponentNameMap.put(CAS.TYPE_NAME_FLOAT_ARRAY, CAS.TYPE_NAME_FLOAT);
    arrayTypeComponentNameMap.put(CAS.TYPE_NAME_LONG_ARRAY, CAS.TYPE_NAME_LONG);
    arrayTypeComponentNameMap.put(CAS.TYPE_NAME_DOUBLE_ARRAY, CAS.TYPE_NAME_DOUBLE);
    arrayTypeComponentNameMap.put(CAS.TYPE_NAME_STRING_ARRAY, CAS.TYPE_NAME_STRING);
  }

  // Current implementation has online update. Look-up could be made
  // more efficient by computing some tables, but the assumption is
  // that the type system will not be queried often enough to justify
  // the effort.

  private SymbolTable typeNameST; // Symbol table of type names

  // Symbol table of feature names, containing only one entry per feature,
  // i.e.,
  // its normal form.
  private SymbolTable featureNameST;

  // A map from the full space of feature names to feature codes. A feature
  // may
  // be know by many different names (one for each subtype of the type the
  // feature is declared on).
  private StringToIntMap featureMap;

  private ArrayList tree; // Collection of IntVectors encoding type tree

  private ArrayList subsumes; // Collection of BitSets for subsumption relation

  private IntVector intro;

  // Indicates which type introduces a feature (domain)
  private IntVector featRange; // Indicates range type of features

  private ArrayList approp; // For each type, an IntVector of appropriate

  // features

  // Code of root of hierarchy (will be 1 with current implementation)
  private int top;

  // An ArrayList (unsynchronized) of TypeImpl objects.
  private ArrayList types;

  // An ArrayList (unsynchronized) of FeatureImpl objects.
  private ArrayList features;

  // List of parent types.
  private final IntVector parents;

  // String sets for string subtypes.
  private final ArrayList stringSets;

  // This map contains an entry for every subtype of the string type. The value is a pointer into
  // the stringSets array list.
  private final IntRedBlackTree stringSetMap;

  // For each type, remember if an array of this component type has already
  // been created.
  private final IntRedBlackTree componentToArrayTypeMap;

  // A mapping from array types to their component types.
  private final IntRedBlackTree arrayToComponentTypeMap;

  // A mapping from array type codes to array type objects.
  private final RedBlackTree arrayCodeToTypeMap;

  // Is the type system locked?
  private boolean locked = false;

  private static final int LEAST_TYPE_CODE = 1;

  // private static final int INVALID_TYPE_CODE = 0;
  private static final int LEAST_FEATURE_CODE = 1;

  private int numCommittedTypes = 0;

  final CASMetadata casMetadata; // needs to be visible in package

  boolean areBuiltInTypesSetup = false;

  TypeImpl intType;

  TypeImpl stringType;

  TypeImpl floatType;

  TypeImpl arrayBaseType;

  TypeImpl intArrayType;

  TypeImpl floatArrayType;

  TypeImpl stringArrayType;

  TypeImpl fsArrayType;

  TypeImpl sofaType;

  TypeImpl annotType;

  TypeImpl annotBaseType;

  TypeImpl docType;

  FeatureImpl startFeat;

  FeatureImpl endFeat;

  FeatureImpl langFeat;

  FeatureImpl sofaNum;

  TypeImpl byteType;

  TypeImpl byteArrayType;

  TypeImpl booleanType;

  TypeImpl booleanArrayType;

  TypeImpl shortType;

  TypeImpl shortArrayType;

  TypeImpl longType;

  TypeImpl longArrayType;

  TypeImpl doubleType;

  TypeImpl doubleArrayType;

  // int topTypeCode;
  int intTypeCode = LowLevelTypeSystem.UNKNOWN_TYPE_CODE;

  int stringTypeCode = LowLevelTypeSystem.UNKNOWN_TYPE_CODE;

  int floatTypeCode = LowLevelTypeSystem.UNKNOWN_TYPE_CODE;

  int arrayBaseTypeCode = LowLevelTypeSystem.UNKNOWN_TYPE_CODE;

  int intArrayTypeCode = LowLevelTypeSystem.UNKNOWN_TYPE_CODE;

  int floatArrayTypeCode = LowLevelTypeSystem.UNKNOWN_TYPE_CODE;

  int stringArrayTypeCode = LowLevelTypeSystem.UNKNOWN_TYPE_CODE;

  int fsArrayTypeCode = LowLevelTypeSystem.UNKNOWN_TYPE_CODE;

  int sofaTypeCode = LowLevelTypeSystem.UNKNOWN_TYPE_CODE;

  int annotTypeCode = LowLevelTypeSystem.UNKNOWN_TYPE_CODE;

  int annotBaseTypeCode = LowLevelTypeSystem.UNKNOWN_TYPE_CODE;

  int byteTypeCode = LowLevelTypeSystem.UNKNOWN_TYPE_CODE;

  int booleanTypeCode = LowLevelTypeSystem.UNKNOWN_TYPE_CODE;

  int shortTypeCode = LowLevelTypeSystem.UNKNOWN_TYPE_CODE;

  int longTypeCode = LowLevelTypeSystem.UNKNOWN_TYPE_CODE;

  int doubleTypeCode = LowLevelTypeSystem.UNKNOWN_TYPE_CODE;

  int byteArrayTypeCode = LowLevelTypeSystem.UNKNOWN_TYPE_CODE;

  int booleanArrayTypeCode = LowLevelTypeSystem.UNKNOWN_TYPE_CODE;

  int shortArrayTypeCode = LowLevelTypeSystem.UNKNOWN_TYPE_CODE;

  int longArrayTypeCode = LowLevelTypeSystem.UNKNOWN_TYPE_CODE;

  int doubleArrayTypeCode = LowLevelTypeSystem.UNKNOWN_TYPE_CODE;

  public int sofaNumFeatCode = LowLevelTypeSystem.UNKNOWN_TYPE_CODE;  // ref from another pkg

  int sofaIdFeatCode = LowLevelTypeSystem.UNKNOWN_TYPE_CODE;

  int sofaMimeFeatCode = LowLevelTypeSystem.UNKNOWN_TYPE_CODE;

  int sofaUriFeatCode = LowLevelTypeSystem.UNKNOWN_TYPE_CODE;

  int sofaArrayFeatCode = LowLevelTypeSystem.UNKNOWN_TYPE_CODE;

  public int annotSofaFeatCode = LowLevelTypeSystem.UNKNOWN_TYPE_CODE; // ref from another pkg

  int startFeatCode = LowLevelTypeSystem.UNKNOWN_TYPE_CODE;

  int endFeatCode = LowLevelTypeSystem.UNKNOWN_TYPE_CODE;

  int langFeatCode = LowLevelTypeSystem.UNKNOWN_TYPE_CODE;

  /**
   * Default constructor.
   * 
   * @deprecated Use 0 arg constructor. Type Systems are shared by many CASes, and can't point to
   *             one. Change also your possible calls to ts.commit() - see comment on that method.
   */
  @Deprecated
public TypeSystemImpl(CASImpl cas) {
    this();
  }

  public TypeSystemImpl() {
    // Changed numbering to start at 1. Hope this doesn't break
    // anything. If it does, I know who's fault it is...
    this.typeNameST = new SymbolTable(1);
    this.featureNameST = new SymbolTable(1);
    this.featureMap = new StringToIntMap();
    // In each Vector, add null as first element, since we start
    // counting at 1.
    this.tree = new ArrayList();
    this.tree.add(null);
    this.subsumes = new ArrayList();
    this.subsumes.add(null);
    this.intro = new IntVector();
    this.intro.add(0);
    this.featRange = new IntVector();
    this.featRange.add(0);
    this.approp = new ArrayList();
    this.approp.add(null);
    this.types = new ArrayList();
    this.types.add(null);
    this.features = new ArrayList();
    this.features.add(null);
    this.stringSets = new ArrayList();
    this.stringSetMap = new IntRedBlackTree();
    this.componentToArrayTypeMap = new IntRedBlackTree();
    this.arrayToComponentTypeMap = new IntRedBlackTree();
    this.arrayCodeToTypeMap = new RedBlackTree();
    this.parents = new IntVector();
    this.parents.add(0);

    this.casMetadata = new CASMetadata(this);
    // load in built-in types
    CASImpl.setupTSDefault(this);
    initTypeVariables();
  }

  // only built-in types here; can be called before
  // type system is committed, as long as the built-in ones
  // are defined.
  final void initTypeVariables() {
    // Type objects.
    // this.ts.topType = (TypeImpl) this.ts.getTopType(); // never used
    this.intType = (TypeImpl) getType(CAS.TYPE_NAME_INTEGER);
    this.stringType = (TypeImpl) getType(CAS.TYPE_NAME_STRING);
    this.floatType = (TypeImpl) getType(CAS.TYPE_NAME_FLOAT);
    this.arrayBaseType = (TypeImpl) getType(CAS.TYPE_NAME_ARRAY_BASE);
    this.intArrayType = (TypeImpl) getType(CAS.TYPE_NAME_INTEGER_ARRAY);
    this.floatArrayType = (TypeImpl) getType(CAS.TYPE_NAME_FLOAT_ARRAY);
    this.stringArrayType = (TypeImpl) getType(CAS.TYPE_NAME_STRING_ARRAY);
    this.fsArrayType = (TypeImpl) getType(CAS.TYPE_NAME_FS_ARRAY);
    this.sofaType = (TypeImpl) getType(CAS.TYPE_NAME_SOFA);
    this.annotType = (TypeImpl) getType(CAS.TYPE_NAME_ANNOTATION);
    this.sofaNum = (FeatureImpl) getFeatureByFullName(CAS.FEATURE_FULL_NAME_SOFANUM);
    this.annotBaseType = (TypeImpl) getType(CAS.TYPE_NAME_ANNOTATION_BASE);
    this.startFeat = (FeatureImpl) getFeatureByFullName(CAS.FEATURE_FULL_NAME_BEGIN);
    this.endFeat = (FeatureImpl) getFeatureByFullName(CAS.FEATURE_FULL_NAME_END);
    this.langFeat = (FeatureImpl) getFeatureByFullName(CAS.FEATURE_FULL_NAME_LANGUAGE);
    this.docType = (TypeImpl) getType(CAS.TYPE_NAME_DOCUMENT_ANNOTATION);

    this.byteType = (TypeImpl) getType(CAS.TYPE_NAME_BYTE);
    this.byteArrayType = (TypeImpl) getType(CAS.TYPE_NAME_BYTE_ARRAY);
    this.booleanType = (TypeImpl) getType(CAS.TYPE_NAME_BOOLEAN);
    this.booleanArrayType = (TypeImpl) getType(CAS.TYPE_NAME_BOOLEAN_ARRAY);
    this.shortType = (TypeImpl) getType(CAS.TYPE_NAME_SHORT);
    this.shortArrayType = (TypeImpl) getType(CAS.TYPE_NAME_SHORT_ARRAY);
    this.longType = (TypeImpl) getType(CAS.TYPE_NAME_LONG);
    this.longArrayType = (TypeImpl) getType(CAS.TYPE_NAME_LONG_ARRAY);
    this.doubleType = (TypeImpl) getType(CAS.TYPE_NAME_DOUBLE);
    this.doubleArrayType = (TypeImpl) getType(CAS.TYPE_NAME_DOUBLE_ARRAY);

    // Type codes.
    initTypeCodeVars();
  }

  private final void initTypeCodeVars() {
    this.intTypeCode = this.intType.getCode();
    this.stringTypeCode = this.stringType.getCode();
    this.floatTypeCode = this.floatType.getCode();
    // this.arrayBaseTypeCode = arrayBaseType.getCode();
    this.intArrayTypeCode = this.intArrayType.getCode();
    this.floatArrayTypeCode = this.floatArrayType.getCode();
    this.stringArrayTypeCode = this.stringArrayType.getCode();
    this.fsArrayTypeCode = this.fsArrayType.getCode();
    this.sofaTypeCode = this.sofaType.getCode();
    this.annotTypeCode = this.annotType.getCode();
    this.annotBaseTypeCode = this.annotBaseType.getCode();

    this.byteArrayTypeCode = this.byteArrayType.getCode();
    this.byteTypeCode = this.byteType.getCode();
    this.booleanTypeCode = this.booleanType.getCode();
    this.booleanArrayTypeCode = this.booleanArrayType.getCode();
    this.shortTypeCode = this.shortType.getCode();
    this.shortArrayTypeCode = this.shortArrayType.getCode();
    this.longTypeCode = this.longType.getCode();
    this.longArrayTypeCode = this.longArrayType.getCode();
    this.doubleTypeCode = this.doubleType.getCode();
    this.doubleArrayTypeCode = this.doubleArrayType.getCode();

    this.arrayBaseTypeCode = this.arrayBaseType.getCode();

    final Type sofaT = this.sofaType;
    this.sofaNumFeatCode = ll_getCodeForFeature(sofaT
        .getFeatureByBaseName(CAS.FEATURE_BASE_NAME_SOFANUM));
    this.sofaIdFeatCode = ll_getCodeForFeature(sofaT
        .getFeatureByBaseName(CAS.FEATURE_BASE_NAME_SOFAID));
    this.sofaMimeFeatCode = ll_getCodeForFeature(sofaT
        .getFeatureByBaseName(CAS.FEATURE_BASE_NAME_SOFAMIME));
    this.sofaUriFeatCode = ll_getCodeForFeature(sofaT
        .getFeatureByBaseName(CAS.FEATURE_BASE_NAME_SOFAURI));
    this.sofaArrayFeatCode = ll_getCodeForFeature(sofaT
        .getFeatureByBaseName(CAS.FEATURE_BASE_NAME_SOFAARRAY));
    this.annotSofaFeatCode = ll_getCodeForFeature(this.annotBaseType
        .getFeatureByBaseName(CAS.FEATURE_BASE_NAME_SOFA));
    this.startFeatCode = ll_getCodeForFeature(this.annotType
        .getFeatureByBaseName(CAS.FEATURE_BASE_NAME_BEGIN));
    this.endFeatCode = ll_getCodeForFeature(this.annotType
        .getFeatureByBaseName(CAS.FEATURE_BASE_NAME_END));
    this.langFeatCode = ll_getCodeForFeature(this.docType
        .getFeatureByBaseName(CAS.FEATURE_BASE_NAME_LANGUAGE));
  }

  // Some implementation helpers for users of the type system.
  final int getSmallestType() {
    return LEAST_TYPE_CODE;
  }

  final int getSmallestFeature() {
    return LEAST_FEATURE_CODE;
  }

  final int getTypeArraySize() {
    return getNumberOfTypes() + getSmallestType();
  }

  public Vector getIntroFeatures(Type type) {
    Vector feats = new Vector();
    List appropFeats = type.getFeatures();
    final int max = appropFeats.size();
    Feature feat;
    for (int i = 0; i < max; i++) {
      feat = (Feature) appropFeats.get(i);
      if (feat.getDomain() == type) {
        feats.add(feat);
      }
    }
    return feats;
  }

  public Type getParent(Type t) {
    return ((TypeImpl) t).getSuperType();
  }

  public int ll_getParentType(int typeCode) {
    return this.parents.get(typeCode);
  }

  int ll_computeArrayParentFromComponentType(int componentType) {
    if (ll_isPrimitiveType(componentType) ||
    // note: not using this.top - until we can confirm this is set
        // in all cases
        (ll_getTypeForCode(componentType).getName().equals(CAS.TYPE_NAME_TOP))) {
      return this.arrayBaseTypeCode;
    }
    // is a subtype of FSArray.
    // note: not using this.fsArray - until we can confirm this is set in
    // all cases
    return this.fsArrayTypeCode;
    // return ll_getArrayType(ll_getParentType(componentType));
  }

  /**
   * Check if feature is appropriate for type (i.e., type is subsumed by domain type of feature).
   */
  public boolean isApprop(int type, int feat) {
    return subsumes(intro(feat), type);
  }

  public final int getLargestTypeCode() {
    return getNumberOfTypes();
  }

  public boolean isType(int type) {
    return ((type > 0) && (type <= getLargestTypeCode()));
  }

  /**
   * Get a type object for a given name.
   * 
   * @param typeName
   *          The name of the type.
   * @return A type object, or <code>null</code> if no such type exists.
   */
  public Type getType(String typeName) {
    final int typeCode = ll_getCodeForTypeName(typeName);
    if (typeCode < LEAST_TYPE_CODE) {
      return null;
    }
    return (Type) this.types.get(typeCode);
  }

  /**
   * Get an feature object for a given code.
   * 
   * @param featCode
   *          The code of the feature.
   * @return A feature object, or <code>null</code> if no such feature exists.
   */
//  public Feature getFeature(int featCode) {
//    return (Feature) this.features.get(featCode);
//  }

  /**
   * Get an feature object for a given name.
   * 
   * @param featureName
   *          The name of the feature.
   * @return An feature object, or <code>null</code> if no such feature exists.
   */
  public Feature getFeatureByFullName(String featureName) {
    // if (!this.featureMap.containsKey(featureName)) {
    // return null;
    // }
    // final int featCode = this.featureMap.get(featureName);
    // return (Feature) this.features.get(featCode);
    // will return null if feature not present because
    // the featureMap.get will return 0, and
    // getFeature returns null for code of 0
    return ll_getFeatureForCode(this.featureMap.get(featureName));
  }

  private static final String getArrayTypeName(String typeName) {
    final String arrayTypeName = (String) arrayComponentTypeNameMap.get(typeName);
    return (null == arrayTypeName) ? typeName + arrayTypeSuffix : arrayTypeName;
    // if (arrayComponentTypeNameMap.containsKey(typeName)) {
    // return (String) arrayComponentTypeNameMap.get(typeName);
    // }
    // return typeName + arrayTypeSuffix;
  }
  
  static final String getArrayComponentName(String arrayTypeName) {
  	return arrayTypeName.substring(0, arrayTypeName.length() - 2);
  }
  
  static boolean isArrayTypeNameButNotBuiltIn(String typeName) {
  	return typeName.endsWith(arrayTypeSuffix);
  }

  private static final String getBuiltinArrayComponent(String typeName) {
    // if typeName is not contained in the map, the "get" returns null
    // if (arrayTypeComponentNameMap.containsKey(typeName)) {
    return (String) arrayTypeComponentNameMap.get(typeName);
    // }
    // return null;
  }

  /**
   * Add a new type to the type system.
   * 
   * @param typeName
   *          The name of the new type.
   * @param mother
   *          The type node under which the new type should be attached.
   * @return The new type, or <code>null</code> if <code>typeName</code> is already in use.
   */
  public Type addType(String typeName, Type mother) throws CASAdminException {
    if (this.locked) {
      throw new CASAdminException(CASAdminException.TYPE_SYSTEM_LOCKED);
    }
    if (mother.isInheritanceFinal()) {
      CASAdminException e = new CASAdminException(CASAdminException.TYPE_IS_INH_FINAL);
      e.addArgument(mother.getName());
      throw e;
    }
    // Check type name syntax.
    // Handle the built-in array types, like BooleanArray, FSArray, etc.
    String componentTypeName = getBuiltinArrayComponent(typeName);
    if (componentTypeName != null) {
      return getArrayType(getType(componentTypeName));
    }
    checkTypeSyntax(typeName);
    final int typeCode = this.addType(typeName, ((TypeImpl) mother).getCode());
    if (typeCode < this.typeNameST.getStart()) {
      return null;
    }
    return (Type) this.types.get(typeCode);
  }

  /**
   * Method checkTypeSyntax.
   * 
   * @param typeName
   */
  private void checkTypeSyntax(String name) throws CASAdminException {
    if (!TypeSystemUtils.isTypeName(name)) {
      CASAdminException e = new CASAdminException(CASAdminException.BAD_TYPE_SYNTAX);
      e.addArgument(name);
      throw e;
    }
  }

  int addType(String name, int superType) {
    return addType(name, superType, false);
  }

  /**
   * Internal code for adding a new type. Warning: no syntax check on type name, must be done by
   * caller. This method is not private because it's used by the serialization code.
   */
  int addType(String name, int superType, boolean isStringType) {
    if (this.typeNameST.contains(name)) {
      return -1;
    }
    // assert (isType(superType)); //: "Supertype is not a known type:
    // "+superType;
    // Add the new type to the symbol table.
    final int type = this.typeNameST.set(name);
    // Create space for new type.
    newType();
    // Add an edge to the tree.
    ((IntVector) this.tree.get(superType)).add(type);
    // Update subsumption relation.
    updateSubsumption(type, superType);
    // Add inherited features.
    final IntVector superApprop = (IntVector) this.approp.get(superType);
    // superApprop.add(0);
    final IntVector typeApprop = (IntVector) this.approp.get(type);
    // typeApprop.add(0);
    final int max = superApprop.size();
    int featCode;
    for (int i = 0; i < max; i++) {
      featCode = superApprop.get(i);
      typeApprop.add(featCode);
      // Add inherited feature names.
      String feat = name + TypeSystem.FEATURE_SEPARATOR + ll_getFeatureForCode(featCode).getShortName();
      // System.out.println("Adding name: " + feat);
      this.featureMap.put(feat, featCode);
    }
    TypeImpl t;
    if (isStringType) {
      final int stringSetCode = this.stringSets.size();
      this.stringSetMap.put(type, stringSetCode);
      t = new StringTypeImpl(name, type, this);
    } else {
      t = new TypeImpl(name, type, this);
    }
    this.types.add(t);
    this.parents.add(superType);
    this.numCommittedTypes = this.types.size();
    return type;
  }

  public Feature addFeature(String featureName, Type domainType, Type rangeType)
      throws CASAdminException {
    return addFeature(featureName, domainType, rangeType, true);
  }

  /**
   * @see TypeSystemMgr#addFeature(String, Type, Type)
   */
  public Feature addFeature(String featureName, Type domainType, Type rangeType,
      boolean multipleReferencesAllowed) throws CASAdminException {
    // assert(featureName != null);
    // assert(domainType != null);
    // assert(rangeType != null);
    if (this.locked) {
      throw new CASAdminException(CASAdminException.TYPE_SYSTEM_LOCKED);
    }
    Feature f = domainType.getFeatureByBaseName(featureName);
    if (f != null && f.getRange().equals(rangeType)) {
      return f;
    }
    if (domainType.isFeatureFinal()) {
      CASAdminException e = new CASAdminException(CASAdminException.TYPE_IS_FEATURE_FINAL);
      e.addArgument(domainType.getName());
      throw e;
    }
    checkFeatureNameSyntax(featureName);
    final int featCode = this.addFeature(featureName, ((TypeImpl) domainType).getCode(),
        ((TypeImpl) rangeType).getCode(), multipleReferencesAllowed);
    if (featCode < this.featureNameST.getStart()) {
      return null;
    }
    return (Feature) this.features.get(featCode);
  }

  /**
   * Method checkFeatureNameSyntax.
   */
  private void checkFeatureNameSyntax(String name) throws CASAdminException {
    if (!TypeSystemUtils.isIdentifier(name)) {
      CASAdminException e = new CASAdminException(CASAdminException.BAD_FEATURE_SYNTAX);
      e.addArgument(name);
      throw e;
    }
  }

  /**
   * Get an iterator over all types, in no particular order.
   * 
   * @return The iterator.
   */
  public Iterator getTypeIterator() {
    Iterator it = new ListIterator(this.types, this.numCommittedTypes);
    // The first element is null, so skip it.
    it.next();
    return it;
  }

  public Iterator getFeatures() {
    Iterator it = this.features.iterator();
    // The first element is null, so skip it.
    it.next();
    return it;
  }

  /**
   * Get the top type, i.e., the root of the type system.
   * 
   * @return The top type.
   */
  public Type getTopType() {
    return (Type) this.types.get(this.top);
  }

  /**
   * Return the list of all types subsumed by the input type. Note: the list does not include the
   * type itself.
   * 
   * @param type
   *          Input type.
   * @return The list of types subsumed by <code>type</code>.
   */
  public List getProperlySubsumedTypes(Type type) {
    ArrayList subList = new ArrayList();
    Iterator typeIt = getTypeIterator();
    while (typeIt.hasNext()) {
      Type t = (Type) typeIt.next();
      if (type != t && subsumes(type, t)) {
        subList.add(t);
      }
    }
    return subList;
  }

  /**
   * Get a vector of the types directly subsumed by a given type.
   * 
   * @param type
   *          The input type.
   * @return A vector of the directly subsumed types.
   */
  public Vector getDirectlySubsumedTypes(Type type) {
    return new Vector(getDirectSubtypes(type));
  }

  public List getDirectSubtypes(Type type) {
    if (type.isArray()) {
      return new ArrayList();
    }
    ArrayList list = new ArrayList();
    IntVector sub = (IntVector) this.tree.get(((TypeImpl) type).getCode());
    final int max = sub.size();
    for (int i = 0; i < max; i++) {
      list.add(this.types.get(sub.get(i)));
    }
    return list;
  }

  public boolean directlySubsumes(int t1, int t2) {
    IntVector sub = (IntVector) this.tree.get(t1);
    return sub.contains(t2);
  }

  /**
   * Does one type inherit from the other?
   * 
   * @param superType
   *          Supertype.
   * @param subType
   *          Subtype.
   * @return <code>true</code> iff <code>sub</code> inherits from <code>super</code>.
   */
  public boolean subsumes(Type superType, Type subType) {
    // assert(superType != null);
    // assert(subType != null);
    return this.subsumes(((TypeImpl) superType).getCode(), ((TypeImpl) subType).getCode());
  }

  /**
   * Get an array of the appropriate features for this type.
   */
  public int[] ll_getAppropriateFeatures(int type) {
    if (type < LEAST_TYPE_CODE || type > getNumberOfTypes()) {
      return null;
    }
    // We have to copy the array since we don't have const.
    return ((IntVector) this.approp.get(type)).toArrayCopy();
  }

  /**
   * @return An offset <code>&gt;0</code> if <code>feat</code> exists; <code>0</code>, else.
   */
  int getFeatureOffset(int feat) {
    return ((IntVector) this.approp.get(this.intro.get(feat))).position(feat) + 1;
  }

  /**
   * Get the overall number of features defined in the type system.
   */
  public int getNumberOfFeatures() {
    return this.featureNameST.size();
  }

  /**
   * Get the overall number of types defined in the type system.
   */
  public int getNumberOfTypes() {
    return this.typeNameST.size();
  }

  /**
   * Get the domain type for a feature.
   */
  public int intro(int feat) {
    return this.intro.get(feat);
  }

  /**
   * Get the range type for a feature.
   */
  public int range(int feat) {
    return this.featRange.get(feat);
  }

  // Unification is trivial, since we don't have multiple inheritance.
  public int unify(int t1, int t2) {
    if (this.subsumes(t1, t2)) {
      return t2;
    } else if (this.subsumes(t2, t1)) {
      return t1;
    } else {
      return -1;
    }
  }

  int addFeature(String shortName, int domain, int range) {
    return addFeature(shortName, domain, range, true);
  }

  /**
   * Add a new feature to the type system.
   */
  int addFeature(String shortName, int domain, int range, boolean multiRefsAllowed) {
    // Since we just looked up the domain in the symbol table, we know it
    // exists.
    String name = this.typeNameST.getSymbol(domain) + TypeSystem.FEATURE_SEPARATOR + shortName;
    // Create a list of the domain type and all its subtypes.
    // Type t = getType(domain);
    // if (t == null) {
    // System.out.println("Type is null");
    // }
    List typesLocal = getProperlySubsumedTypes(ll_getTypeForCode(domain));
    typesLocal.add(ll_getTypeForCode(domain));
    // For each type, check that the feature doesn't already exist.
    int max = typesLocal.size();
    for (int i = 0; i < max; i++) {
      String featureName = ((Type) typesLocal.get(i)).getName() + FEATURE_SEPARATOR + shortName;
      if (this.featureMap.containsKey(featureName)) {
        // We have already added this feature. If the range of the
        // duplicate
        // feature is identical, we don't do anything and just return.
        // Else,
        // we throw an exception.
        Feature oldFeature = getFeatureByFullName(featureName);
        Type oldDomain = oldFeature.getDomain();
        Type oldRange = oldFeature.getRange();
        if (range == ll_getCodeForType(oldRange)) {
          return -1;
        }
        CASAdminException e = new CASAdminException(CASAdminException.DUPLICATE_FEATURE);
        e.addArgument(shortName);
        e.addArgument(ll_getTypeForCode(domain).getName());
        e.addArgument(ll_getTypeForCode(range).getName());
        e.addArgument(oldDomain.getName());
        e.addArgument(oldRange.getName());
        throw e;
      }
    } // Add name to symbol table.
    int feat = this.featureNameST.set(name);
    // Add entries for all subtypes.
    for (int i = 0; i < max; i++) {
      this.featureMap.put(((Type) typesLocal.get(i)).getName() + FEATURE_SEPARATOR + shortName,
          feat);
    }
    this.intro.add(domain);
    this.featRange.add(range);
    max = this.typeNameST.size();
    for (int i = 1; i <= max; i++) {
      if (subsumes(domain, i)) {
        ((IntVector) this.approp.get(i)).add(feat);
      }
    }
    this.features.add(new FeatureImpl(feat, name, this, multiRefsAllowed));
    return feat;
  }

  /**
   * Add a top type to the (empty) type system.
   */
  public Type addTopType(String name) {
    final int code = this.addTopTypeInternal(name);
    if (code < 1) {
      return null;
    }
    return (Type) this.types.get(code);
  }

  private int addTopTypeInternal(String name) {
    if (this.typeNameST.size() > 0) {
      // System.out.println("Size of type table > 0.");
      return 0;
    } // Add name of top type to symbol table.
    this.top = this.typeNameST.set(name);
    // System.out.println("Size of name table is: " + typeNameST.size());
    // assert (typeNameST.size() == 1);
    // System.out.println("Code of top type is: " + this.top);
    // Create space for top type.
    newType();
    // Make top subsume itself.
    addSubsubsumption(this.top, this.top);
    this.types.add(new TypeImpl(name, this.top, this));
    this.parents.add(LowLevelTypeSystem.UNKNOWN_TYPE_CODE);
    this.numCommittedTypes = this.types.size();
    return this.top;
  }

  /**
   * Check if the first argument subsumes the second
   */
  public boolean subsumes(int superType, int type) {
    return this.ll_subsumes(superType, type);
  }

  private boolean ll_isPrimitiveArrayType(int type) {
    return type == this.floatArrayTypeCode || type == this.intArrayTypeCode
        || type == this.booleanArrayTypeCode || type == this.shortArrayTypeCode
        || type == this.byteArrayTypeCode || type == this.longArrayTypeCode
        || type == this.doubleArrayTypeCode || type == this.stringArrayTypeCode;
  }

  public boolean ll_subsumes(int superType, int type) {
    // Add range check.
    // assert (isType(superType));
    // assert (isType(type));

    // Need special handling for arrays, as they're generated on the fly and
    // not added to the subsumption table.

    // speedup code.
    if (superType == type)
      return true;

    // Yes, the code below is intentional. Until we actually support real
    // arrays of some
    // particular fs,
    // we have FSArray is the supertype of xxxx[] AND
    // xxx[] is the supertype of FSArray
    // (this second relation because all we can generate are instances of
    // FSArray
    // and we must be able to assign them to xxx[] )
    if (superType == this.fsArrayTypeCode) {
      return !ll_isPrimitiveArrayType(type) && ll_isArrayType(type);
    }

    if (type == this.fsArrayTypeCode) {
      return superType == this.top || superType == this.arrayBaseTypeCode
          || (!ll_isPrimitiveArrayType(superType) && ll_isArrayType(superType));
    }

    // at this point, we could have arrays of other primitive types, or
    // arrays of specific types: xxx[]

    final boolean isSuperArray = ll_isArrayType(superType);
    final boolean isSubArray = ll_isArrayType(type);
    if (isSuperArray) {
      if (isSubArray) {
        // If both types are arrays, simply compare the components.
        return ll_subsumes(ll_getComponentType(superType), ll_getComponentType(type));
      }
      // An array can never subsume a non-array.
      return false;
    } else if (isSubArray) {
      // If the subtype is an array, and the supertype is not, then the
      // supertype must be top, or the abstract array base.
      return ((superType == this.top) || (superType == this.arrayBaseTypeCode));
    }
    return ((BitSet) this.subsumes.get(superType)).get(type);
  }

  private void updateSubsumption(int type, int superType) {
    final int max = this.typeNameST.size();
    for (int i = 1; i <= max; i++) {
      if (subsumes(i, superType)) {
        addSubsubsumption(i, type);
      }
    }
    addSubsubsumption(type, type);
  }

  private void addSubsubsumption(int superType, int type) {
    ((BitSet) this.subsumes.get(superType)).set(type);
  }

  private void newType() {
    // The assumption for the implementation is that new types will
    // always be added at the end.
    this.tree.add(new IntVector());
    this.subsumes.add(new BitSet());
    this.approp.add(new IntVector());
  }

  // Only used for serialization code.
  SymbolTable getTypeNameST() {
    return this.typeNameST;
  }

  private final String getTypeString(Type t) {
    return t.getName() + " (" + ll_getCodeForType(t) + ")";
  }

  private final String getFeatureString(Feature f) {
    return f.getName() + " (" + ll_getCodeForFeature(f) + ")";
  }

  /**
   * This writes out the type hierarchy in a human-readable form.
   */
  public String toString() {
    // This code is maximally readable, not maximally efficient.
    StringBuffer buf = new StringBuffer();
    // Print top type.
    buf.append("~" + getTypeString(this.getTopType()) + ";\n");
    // Iterate over types and print declarations.
    final int numTypes = this.typeNameST.size();
    Type t;
    for (int i = 2; i <= numTypes; i++) {
      t = this.ll_getTypeForCode(i);
      buf.append(getTypeString(t) + " < " + getTypeString(this.getParent(t)) + ";\n");
    } // Print feature declarations.
    final int numFeats = this.featureNameST.size();
    Feature f;
    for (int i = 1; i <= numFeats; i++) {
      f = this.ll_getFeatureForCode(i);
      buf.append(getFeatureString(f) + ": " + getTypeString(f.getDomain()) + " > "
          + getTypeString(f.getRange()) + ";\n");
    }
    return buf.toString();
  }

  /**
   * @see org.apache.uima.cas.admin.TypeSystemMgr#commit()
   */
  public void commit() {
    if (this.locked == true) {
      return; // might be called multiple times, but only need to do once
    }
    this.locked = true;
    // because subsumes depends on it
    // and generator initialization uses subsumes
    this.numCommittedTypes = this.types.size(); // do before
    // cas.commitTypeSystem -
    // because it will call the type system iterator
    this.casMetadata.setupFeaturesAndCreatableTypes();
    // ts should never point to a CAS. Many CASes can share one ts.
    // if (this.cas != null) {
    // this.cas.commitTypeSystem();
    // }
  }

  /**
   * @see org.apache.uima.cas.admin.TypeSystemMgr#isCommitted()
   */
  public boolean isCommitted() {
    return this.locked;
  }

  // dangerous, and not needed, not in any interface
//  public void setCommitted(boolean b) {
//    this.locked = b;
//  }

  /**
   * @deprecated
   */
  @Deprecated
public Feature getFeature(String featureName) {
    return getFeatureByFullName(featureName);
  }

  /**
   * @see org.apache.uima.cas.admin.TypeSystemMgr#setFeatureFinal(org.apache.uima.cas.Type)
   */
  public void setFeatureFinal(Type type) {
    ((TypeImpl) type).setFeatureFinal();
  }

  /**
   * @see org.apache.uima.cas.admin.TypeSystemMgr#setInheritanceFinal(org.apache.uima.cas.Type)
   */
  public void setInheritanceFinal(Type type) {
    ((TypeImpl) type).setInheritanceFinal();
  }

  /**
   * @see org.apache.uima.cas.admin.TypeSystemMgr#addStringSubtype
   */
  public Type addStringSubtype(String typeName, String[] stringList) throws CASAdminException {
    // final int stringSetCode = this.stringSets.size();
    Type mother = this.stringType;
    // Check type name syntax.
    checkTypeSyntax(typeName);
    // Create the type.
    final int typeCode = this.addType(typeName, ((TypeImpl) mother).getCode(), true);
    // If the type code is less than 1, it means that a type of that name
    // already exists.
    if (typeCode < this.typeNameST.getStart()) {
      return null;
    } // Get the created type.
    StringTypeImpl type = (StringTypeImpl) this.types.get(typeCode);
    type.setFeatureFinal();
    type.setInheritanceFinal();
    // Sort the String array.
    Arrays.sort(stringList);
    // Add the string array to the string sets.
    this.stringSets.add(stringList);
    return type;
  }

  // public for ref from JCas TOP type,
  // impl FeatureStructureImpl
  public String[] getStringSet(int i) {
    return (String[]) this.stringSets.get(i);
  }

  /*
   * (non-Javadoc)
   * 
   * @see org.apache.uima.cas.TypeSystem#getTypeNameSpace(java.lang.String)
   */
  public TypeNameSpace getTypeNameSpace(String name) {
    if (!TypeSystemUtils.isTypeNameSpaceName(name)) {
      return null;
    }
    return new TypeNameSpaceImpl(name, this);
  }

  /*
   * (non-Javadoc)
   * 
   * @see org.apache.uima.cas.impl.LowLevelTypeSystem#ll_getCodeForTypeName(java.lang.String)
   */
  public int ll_getCodeForTypeName(String typeName) {
    if (typeName == null) {
      throw new NullPointerException();
    }
    return this.typeNameST.get(typeName);
  }

  /*
   * (non-Javadoc)
   * 
   * @see org.apache.uima.cas.impl.LowLevelTypeSystem#ll_getCodeForType(org.apache.uima.cas.Type)
   */
  public int ll_getCodeForType(Type type) {
    return ((TypeImpl) type).getCode();
  }

  public int ll_getCodeForFeatureName(String featureName) {
    if (featureName == null) {
      throw new NullPointerException();
    }
    if (!this.featureMap.containsKey(featureName)) {
      return UNKNOWN_FEATURE_CODE;
    }
    return this.featureMap.get(featureName);
  }

  public int ll_getCodeForFeature(Feature feature) {
    return ((FeatureImpl) feature).getCode();
  }

  public Type ll_getTypeForCode(int typeCode) {
    if (isType(typeCode)) {
      return (Type) this.types.get(typeCode);
    }
    return null;
  }

  private final int getLargestFeatureCode() {
    return this.getNumberOfFeatures();
  }

  final boolean isFeature(int featureCode) {
    return ((featureCode > UNKNOWN_FEATURE_CODE) && (featureCode <= getLargestFeatureCode()));
  }

  public Feature ll_getFeatureForCode(int featureCode) {
    if (isFeature(featureCode)) {
      return (Feature) this.features.get(featureCode);
    }
    return null;
  }

  public int ll_getDomainType(int featureCode) {
    return intro(featureCode);
  }

  public int ll_getRangeType(int featureCode) {
    return range(featureCode);
  }

  public LowLevelTypeSystem getLowLevelTypeSystem() {
    return this;
  }

  public boolean ll_isStringSubtype(int type) {
    return this.stringSetMap.containsKey(type);
  }

  public boolean ll_isRefType(int typeCode) {
    final int typeClass = ll_getTypeClass(typeCode);
    switch (typeClass) {
    case LowLevelCAS.TYPE_CLASS_BOOLEAN:
    case LowLevelCAS.TYPE_CLASS_BYTE:
    case LowLevelCAS.TYPE_CLASS_SHORT:
    case LowLevelCAS.TYPE_CLASS_INT:
    case LowLevelCAS.TYPE_CLASS_FLOAT:
    case LowLevelCAS.TYPE_CLASS_LONG:
    case LowLevelCAS.TYPE_CLASS_DOUBLE:
    case LowLevelCAS.TYPE_CLASS_STRING: {
      return false;
    }
    default: {
      return true;
    }
    }
  }

  public Type getArrayType(Type componentType) {
    final int arrayTypeCode = ll_getArrayType(ll_getCodeForType(componentType));
    if (arrayTypeCode == UNKNOWN_TYPE_CODE) {
      return null;
    }
    return (Type) this.types.get(arrayTypeCode);
  }

  public final int ll_getTypeClass(int typeCode) {
    if (typeCode == this.booleanTypeCode) {
      return LowLevelCAS.TYPE_CLASS_BOOLEAN;
    }
    if (typeCode == this.byteTypeCode) {
      return LowLevelCAS.TYPE_CLASS_BYTE;
    }
    if (typeCode == this.shortTypeCode) {
      return LowLevelCAS.TYPE_CLASS_SHORT;
    }
    if (typeCode == this.intTypeCode) {
      return LowLevelCAS.TYPE_CLASS_INT;
    }
    if (typeCode == this.floatTypeCode) {
      return LowLevelCAS.TYPE_CLASS_FLOAT;
    }
    if (typeCode == this.longTypeCode) {
      return LowLevelCAS.TYPE_CLASS_LONG;
    }
    if (typeCode == this.doubleTypeCode) {
      return LowLevelCAS.TYPE_CLASS_DOUBLE;
    }
    // false if string type code not yet set up (during initialization)
    //   need this to avoid NPE in subsumes
    if ((this.stringTypeCode != LowLevelTypeSystem.UNKNOWN_TYPE_CODE) &&
          ll_subsumes(this.stringTypeCode, typeCode)) {
      return LowLevelCAS.TYPE_CLASS_STRING;
    }
    if (typeCode == this.booleanArrayTypeCode) {
      return LowLevelCAS.TYPE_CLASS_BOOLEANARRAY;
    }
    if (typeCode == this.byteArrayTypeCode) {
      return LowLevelCAS.TYPE_CLASS_BYTEARRAY;
    }
    if (typeCode == this.shortArrayTypeCode) {
      return LowLevelCAS.TYPE_CLASS_SHORTARRAY;
    }
    if (typeCode == this.intArrayTypeCode) {
      return LowLevelCAS.TYPE_CLASS_INTARRAY;
    }
    if (typeCode == this.floatArrayTypeCode) {
      return LowLevelCAS.TYPE_CLASS_FLOATARRAY;
    }
    if (typeCode == this.longArrayTypeCode) {
      return LowLevelCAS.TYPE_CLASS_LONGARRAY;
    }
    if (typeCode == this.doubleArrayTypeCode) {
      return LowLevelCAS.TYPE_CLASS_DOUBLEARRAY;
    }
    if (typeCode == this.stringArrayTypeCode) {
      return LowLevelCAS.TYPE_CLASS_STRINGARRAY;
    }
    if (ll_isArrayType(typeCode)) {
      return LowLevelCAS.TYPE_CLASS_FSARRAY;
    }
    return LowLevelCAS.TYPE_CLASS_FS;
  }

  public int ll_getArrayType(int componentTypeCode) {
    if (this.componentToArrayTypeMap.containsKey(componentTypeCode)) {
      return this.componentToArrayTypeMap.get(componentTypeCode);
    }
    return addArrayType(ll_getTypeForCode(componentTypeCode),
        ll_getTypeForCode(ll_computeArrayParentFromComponentType(componentTypeCode)));
  }

  int addArrayType(Type componentType, Type mother) {
    return ll_addArrayType(ll_getCodeForType(componentType), ll_getCodeForType(mother));
  }

  int ll_addArrayType(int componentTypeCode, int motherCode) {

    if (!ll_isValidTypeCode(componentTypeCode)) {
      return UNKNOWN_TYPE_CODE;
    }
    // The array type is new and needs to be created.
    String arrayTypeName = getArrayTypeName(ll_getTypeForCode(componentTypeCode).getName());
    int arrayTypeCode = this.typeNameST.set(arrayTypeName);
    this.componentToArrayTypeMap.put(componentTypeCode, arrayTypeCode);
    this.arrayToComponentTypeMap.put(arrayTypeCode, componentTypeCode);
    // Dummy call to keep the counts ok. Will never use these data
    // structures for array types.
    newType();
    TypeImpl arrayType = new TypeImpl(arrayTypeName, arrayTypeCode, this);
    this.types.add(arrayType);
    this.parents.add(motherCode);
    if (!isCommitted())
      this.numCommittedTypes = this.types.size();
    this.arrayCodeToTypeMap.put(arrayTypeCode, arrayType);
//    System.out.println("*** adding to arrayCodeToTypeMap: " + arrayType.getName() + ", committed=" + isCommitted());
    // For built-in arrays, we need to add the abstract base array as parent
    // to the inheritance tree. This sucks. Assumptions about the base
    // array are all over the place. Would be nice to just remove it.
    // Add an edge to the tree.
    if (!isCommitted() && motherCode != fsArrayTypeCode ) {
      final int arrayBaseTypeCodeBeforeCommitted = this.arrayBaseTypeCode;
      ((IntVector) this.tree.get(arrayBaseTypeCodeBeforeCommitted)).add(arrayTypeCode);
      // Update subsumption relation.
      updateSubsumption(arrayTypeCode, this.arrayBaseTypeCode);
    }
    return arrayTypeCode;
  }

  public boolean ll_isValidTypeCode(int typeCode) {
    return (this.typeNameST.getSymbol(typeCode) != null)
        || this.arrayToComponentTypeMap.containsKey(typeCode);
  }

  public boolean ll_isArrayType(int typeCode) {
//    if (!ll_isValidTypeCode(typeCode)) {
//      return false;
//    }
    return this.arrayCodeToTypeMap.containsKey(typeCode);
  }

  public int ll_getComponentType(int arrayTypeCode) {
    if (ll_isArrayType(arrayTypeCode)) {
      return this.arrayToComponentTypeMap.get(arrayTypeCode);
    }
    return UNKNOWN_TYPE_CODE;
  }

  /* note that subtypes of String are considered primitive */
  public boolean ll_isPrimitiveType(int typeCode) {
    return !ll_isRefType(typeCode);
  }

  public String[] ll_getStringSet(int typeCode) {
//    if (!ll_isValidTypeCode(typeCode)) {
//      return null;
//    }
    if (!ll_isStringSubtype(typeCode)) {
      return null;
    }
    return (String[]) this.stringSets.get(this.stringSetMap.get(typeCode));
  }

}
