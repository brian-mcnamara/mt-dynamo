package com.salesforce.dynamodbv2.testsupport;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.BatchGetItemRequest;
import com.amazonaws.services.dynamodbv2.model.BatchGetItemResult;
import com.amazonaws.services.dynamodbv2.model.GetItemRequest;
import com.amazonaws.services.dynamodbv2.model.GetItemResult;
import com.amazonaws.services.dynamodbv2.model.KeysAndAttributes;
import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Helper methods for building, getting, and doing type conversions that are frequently necessary in unit tests.
 *
 * @author msgroi
 */
public class TestSupport {

    public static final boolean IS_LOCAL_DYNAMO = true;
    public static final int TIMEOUT_SECONDS = 60;
    public static final String HASH_KEY_FIELD = "hashKeyField";
    // we use a number for HASH_KEY_VALUE since it nicely works with Dynamo's scalar-attribute types (S, N, B)
    public static final String HASH_KEY_VALUE = "1";
    public static final String HASH_KEY_OTHER_VALUE = "2";
    public static final String RANGE_KEY_FIELD = "rangeKeyField";
    public static final String RANGE_KEY_VALUE = "rangeKeyValue";
    public static final String SOME_FIELD = "someField";
    public static final String SOME_FIELD_VALUE = "someValue";
    public static final String SOME_OTHER_FIELD_VALUE = "someOtherValue";
    public static final String INDEX_FIELD = "indexField";
    public static final String INDEX_FIELD_VALUE = "indexFieldValue";

    /*
     * get helper methods
     */

    public static Map<String, AttributeValue> getItem(ScalarAttributeType hashKeyAttrType,
        AmazonDynamoDB amazonDynamoDb,
        String tableName) {
        return getItem(hashKeyAttrType, amazonDynamoDb, tableName, HASH_KEY_VALUE);
    }

    public static Map<String, AttributeValue> getItem(ScalarAttributeType hashKeyAttrType,
        AmazonDynamoDB amazonDynamoDb,
        String tableName,
        String hashKeyValue) {
        return getItem(hashKeyAttrType, amazonDynamoDb, tableName, hashKeyValue, Optional.empty());
    }

    /**
     * Retrieves the item with the provided HK and RK values.
     */
    public static Map<String, AttributeValue> getItem(ScalarAttributeType hashKeyAttrType,
        AmazonDynamoDB amazonDynamoDb,
        String tableName,
        String hashKeyValue,
        Optional<String> rangeKeyValue) {
        final AttributeValue hkAttribute = createHkAttribute(hashKeyAttrType, hashKeyValue);
        Map<String, AttributeValue> keys = getKeys(hkAttribute, rangeKeyValue);
        Map<String, AttributeValue> originalKeys = new HashMap<>(keys);
        GetItemRequest getItemRequest = new GetItemRequest().withTableName(tableName).withKey(keys);
        GetItemResult getItemResult = amazonDynamoDb.getItem(getItemRequest);
        assertEquals(tableName, getItemRequest.getTableName()); // assert no side effects
        assertThat(getItemRequest.getKey(), is(originalKeys)); // assert no side effects
        return getItemResult.getItem();
    }

    private static HashMap<String, AttributeValue> getKeys(AttributeValue hkAttribute, Optional<String> rangeKeyValue) {
        return rangeKeyValue
            .map(rkv -> new HashMap<>(ImmutableMap.of(HASH_KEY_FIELD, hkAttribute,
                RANGE_KEY_FIELD, createStringAttribute(rkv))))
            .orElseGet(() -> new HashMap<>(ImmutableMap.of(HASH_KEY_FIELD, hkAttribute)));
    }

    public static Map<String, AttributeValue> getHkRkItem(ScalarAttributeType hashKeyAttrType,
        AmazonDynamoDB amazonDynamoDb,
        String tableName) {
        return getItem(hashKeyAttrType, amazonDynamoDb, tableName, HASH_KEY_VALUE, Optional.of(RANGE_KEY_VALUE));
    }

    /**
     * Retrieves the items with the provided PKs (as HKs or HK-RK pairs).
     */
    public static Set<Map<String, AttributeValue>> batchGetItem(ScalarAttributeType hashKeyAttrType,
                                                      AmazonDynamoDB amazonDynamoDb,
                                                      String tableName,
                                                      // TODO?: pass these both as a single list
                                                      List<String> hashKeyValue,
                                                      Optional<List<String>> rangeKeyValue) {

        rangeKeyValue.ifPresent(rkv -> Preconditions.checkArgument(hashKeyValue.size() == rkv.size()));
        final List<AttributeValue> hkAttribute = hashKeyValue.stream()
                .map(hkv -> createHkAttribute(hashKeyAttrType, hkv))
                .collect(Collectors.toList());

        List<Map<String, AttributeValue>> keys = new ArrayList<>();
        for (int i = 0; i < hashKeyValue.size(); ++i) {
            final int finalI = i;
            keys.add(getKeys(hkAttribute.get(i), rangeKeyValue.map(rkvs -> rkvs.get(finalI))));
        }
        Set<Map<String, AttributeValue>> originalKeys = new HashSet<>(keys);
        final KeysAndAttributes keysAndAttributes = new KeysAndAttributes();
        keysAndAttributes.setKeys(keys);
        Map<String, KeysAndAttributes> requestItems = ImmutableMap.of(tableName, keysAndAttributes);
        Map<String, KeysAndAttributes> unprocessedKeys = ImmutableMap.of();
        final Set<Map<String, AttributeValue>> resultItems = new HashSet<>();
        do {
            final BatchGetItemRequest batchGetItemRequest = new BatchGetItemRequest().withRequestItems(requestItems);
            final BatchGetItemResult batchGetItemResult = amazonDynamoDb.batchGetItem(batchGetItemRequest);

            final Set<String> tablesInResult = batchGetItemResult.getResponses().keySet();
            assertEquals(1, tablesInResult.size());
            assertEquals(tableName, Iterables.getOnlyElement(tablesInResult));
            resultItems.addAll(batchGetItemResult.getResponses().get(tableName));
            requestItems = batchGetItemResult.getUnprocessedKeys();
        } while (!unprocessedKeys.isEmpty());
        assertEquals(originalKeys, resultItems.stream()
                .map(item -> stripItemToPk(item, rangeKeyValue.isPresent()))
                .collect(Collectors.toSet()));
        return resultItems;
    }

    /**
     * Strip {@code item} down to its PK (i.e., a two-element map with HK and RK keys if {@code hasRk}, o/w a
     * one-element map with just an HK key).
     */
    private static Map<String, AttributeValue> stripItemToPk(Map<String, AttributeValue> item, boolean hasRk) {
        return item.entrySet().stream()
                .filter(p -> p.getKey().equals(HASH_KEY_FIELD) || (hasRk && p.getKey().equals(RANGE_KEY_FIELD)))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /*
     * Poll interval and timeout for DDL operations
     */
    public static int getPollInterval() {
        return (IS_LOCAL_DYNAMO ? 0 : 5);
    }

    /**
     * AttributeValue helper methods.
     */
    public static AttributeValue createHkAttribute(ScalarAttributeType hashKeyAttrType, String value) {
        AttributeValue attr = new AttributeValue();
        switch (hashKeyAttrType) {
            case S:
                attr.setS(value);
                return attr;
            case N:
                attr.setN(value);
                return attr;
            case B:
                attr.setB(UTF_8.encode(value));
                return attr;
            default:
                throw new IllegalArgumentException("unsupported type " + hashKeyAttrType + " encountered");
        }
    }

    /**
     * Converts an AttributeValue into a String.
     */
    public static String attributeValueToString(ScalarAttributeType hashKeyAttrType, AttributeValue attr) {
        switch (hashKeyAttrType) {
            case B:
                String decodedString = UTF_8.decode(attr.getB()).toString();
                attr.getB().rewind(); // rewind so future readers don't get an empty buffer
                return decodedString;
            case N:
                return attr.getN();
            case S:
                return attr.getS();
            default:
                throw new IllegalArgumentException("unsupported type " + hashKeyAttrType + " encountered");
        }
    }

    public static AttributeValue createStringAttribute(String value) {
        return new AttributeValue().withS(value);
    }

    /**
     * Item building helper methods.
     */
    public static Map<String, AttributeValue> buildItemWithSomeFieldValue(ScalarAttributeType hashKeyAttrType,
        String value) {
        Map<String, AttributeValue> item = defaultItem(hashKeyAttrType);
        item.put(SOME_FIELD, createStringAttribute(value));
        return item;
    }

    public static Map<String, AttributeValue> buildItemWithValues(ScalarAttributeType hashKeyAttrType,
        String hashKeyValue,
        Optional<String> rangeKeyValue,
        String someFieldValue) {
        return buildItemWithValues(hashKeyAttrType, hashKeyValue, rangeKeyValue, someFieldValue, Optional.empty());
    }

    /**
     * Builds an item with the provided HK, RK, and someField values.
     */
    public static Map<String, AttributeValue> buildItemWithValues(ScalarAttributeType hashKeyAttrType,
        String hashKeyValue,
        Optional<String> rangeKeyValueOpt,
        String someFieldValue,
        Optional<String> indexFieldValueOpt) {
        Map<String, AttributeValue> item = defaultItem(hashKeyAttrType);
        item.put(HASH_KEY_FIELD, createHkAttribute(hashKeyAttrType, hashKeyValue));
        rangeKeyValueOpt.ifPresent(rangeKeyValue -> item.put(RANGE_KEY_FIELD, createStringAttribute(rangeKeyValue)));
        item.put(SOME_FIELD, createStringAttribute(someFieldValue));
        indexFieldValueOpt.ifPresent(indexFieldValue -> item.put(INDEX_FIELD, createStringAttribute(indexFieldValue)));
        return item;
    }

    /**
     * Builds a map representing an item, setting the HK and RK field names and values to the default and setting the
     * someField value to the value provided.
     */
    public static Map<String, AttributeValue> buildHkRkItemWithSomeFieldValue(ScalarAttributeType hashKeyAttrType,
        String value) {
        Map<String, AttributeValue> item = defaultHkRkItem(hashKeyAttrType);
        item.put(SOME_FIELD, createStringAttribute(value));
        return item;
    }

    /**
     * Builds an map representing an item key, setting the HK field and value to the default.
     */
    public static Map<String, AttributeValue> buildKey(ScalarAttributeType hashKeyAttrType) {
        return new HashMap<>(ImmutableMap.of(HASH_KEY_FIELD, createHkAttribute(hashKeyAttrType,
            HASH_KEY_VALUE)));
    }

    /**
     * Builds a map representing an item, setting the HK and RK field names to the default.
     */
    public static Map<String, AttributeValue> buildHkRkKey(ScalarAttributeType hashKeyAttrType) {
        return new HashMap<>(new HashMap<>(ImmutableMap.of(
            HASH_KEY_FIELD, createHkAttribute(hashKeyAttrType, HASH_KEY_VALUE),
            RANGE_KEY_FIELD, createStringAttribute(RANGE_KEY_VALUE))));
    }

    /*
     * private
     */

    private static Map<String, AttributeValue> defaultItem(ScalarAttributeType hashKeyAttrType) {
        return new HashMap<>(ImmutableMap.of(HASH_KEY_FIELD, createHkAttribute(hashKeyAttrType, HASH_KEY_VALUE),
            SOME_FIELD, createStringAttribute(SOME_FIELD_VALUE)));
    }

    private static Map<String, AttributeValue> defaultHkRkItem(ScalarAttributeType hashKeyAttrType) {
        return new HashMap<>(ImmutableMap.of(HASH_KEY_FIELD, createHkAttribute(hashKeyAttrType, HASH_KEY_VALUE),
            RANGE_KEY_FIELD, createStringAttribute(RANGE_KEY_VALUE),
            SOME_FIELD, createStringAttribute(SOME_FIELD_VALUE)));
    }

}