package org.lilycms.hbaseindex;

import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.filter.BinaryPrefixComparator;
import org.apache.hadoop.hbase.filter.CompareFilter;
import org.apache.hadoop.hbase.filter.RowFilter;
import org.apache.hadoop.hbase.util.Bytes;
import org.lilycms.util.ArgumentValidator;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Allows to query an index, and add entries to it or remove entries from it.
 *
 * <p>An Index instance can be obtained from {@link IndexManager#getIndex}.
 *
 * <p>The Index class <b>is not thread safe</b> for writes, because the underlying
 * HBase HTable is not thread safe for writes.
 *
 */
public class Index {
    private HTable htable;
    private IndexDefinition definition;

    private static final byte[] DUMMY_FAMILY = Bytes.toBytes("dummy");
    private static final byte[] DUMMY_QUALIFIER = Bytes.toBytes("dummy");
    private static final byte[] DUMMY_VALUE = Bytes.toBytes("dummy");

    /** Number of bytes overhead per field. */
    private static final int FIELD_OVERHEAD = 1;

    protected Index(HTable htable, IndexDefinition definition) {
        this.htable = htable;
        this.definition = definition;
    }

    public void addEntry(IndexEntry entry, byte[] targetRowKey) throws IOException {
        ArgumentValidator.notNull(entry, "entry");
        ArgumentValidator.notNull(targetRowKey, "targetRowKey");
        validateIndexEntry(entry);

        byte[] indexKey = buildRowKey(entry, targetRowKey);
        Put put = new Put(indexKey);

        // HBase does not allow to create a row without columns, so add some dummy ones.
        put.add(DUMMY_FAMILY, DUMMY_QUALIFIER, DUMMY_VALUE);

        htable.put(put);
    }

    /**
     * Removes an entry from the index. The contents of the supplied
     * entry and the targetRowKey should exactly match those supplied
     * when creating the index entry.
     */
    public void removeEntry(IndexEntry entry, byte[] targetRowKey) throws IOException {
        ArgumentValidator.notNull(entry, "entry");
        ArgumentValidator.notNull(targetRowKey, "targetRowKey");
        validateIndexEntry(entry);

        byte[] indexKey = buildRowKey(entry, targetRowKey);
        Delete delete = new Delete(indexKey);
        htable.delete(delete);
    }

    private void validateIndexEntry(IndexEntry indexEntry) {
        for (Map.Entry<String, Object> entry : indexEntry.getValues().entrySet()) {
            IndexFieldDefinition fieldDef = definition.getField(entry.getKey());
            if (fieldDef == null) {
                throw new MalformedIndexEntryException("Index entry contains a field that is not part of " +
                        "the index definition: " + entry.getKey());
            }

            if (entry.getValue() != null) {
                if (!fieldDef.getType().supportsType(entry.getValue().getClass())) {
                    throw new MalformedIndexEntryException("Index entry for field " + entry.getKey() + " contains" +
                            " a value of an incorrect type. Expected: " + fieldDef.getType().getType().getName() +
                            ", found: " + entry.getValue().getClass().getName());
                }
            }
        }
    }

    /**
     * Build the index row key.
     *
     * <p>The format is as follows:
     *
     * <pre>
     * ([1 byte field flags][fixed length value as bytes])*[target key]
     * </pre>
     *
     * <p>The field flags are currently used to mark if a field is null
     * or not. If a field is null, its value will be encoded as all-zero bits.
     */
    private byte[] buildRowKey(IndexEntry entry, byte[] targetKey) {
        // calculate size of the index key
        int keyLength = targetKey.length + getIndexKeyLength();

        byte[] indexKey = new byte[keyLength];

        // put data in the key
        int offset = 0;
        for (IndexFieldDefinition fieldDef : definition.getFields()) {
            Object value = entry.getValue(fieldDef.getName());
            offset = putField(indexKey, offset, fieldDef, value);
        }

        // put target key in the key
        System.arraycopy(targetKey, 0, indexKey, offset, targetKey.length);

        return indexKey;
    }

    private int getIndexKeyLength() {
        int length = 0;
        for (IndexFieldDefinition fieldDef : definition.getFields()) {
            length += fieldDef.getLength() + FIELD_OVERHEAD;
        }
        return length;
    }

    private int putField(byte[] bytes, int offset, IndexFieldDefinition fieldDef, Object value) {
        return putField(bytes, offset, fieldDef, value, true);
    }

    private int putField(byte[] bytes, int offset, IndexFieldDefinition fieldDef, Object value, boolean fillFieldLength) {
        if (value == null) {
            bytes[offset] = setNullFlag((byte)0);
        }
        offset++;

        if (value != null) {
            offset = fieldDef.toBytes(bytes, offset, value, fillFieldLength);
        } else if (fillFieldLength) { // and value is null
            offset += fieldDef.getLength();
        }

        return offset;
    }

    private byte setNullFlag(byte flags) {
        return (byte)(flags | 0x01);
    }

    public QueryResult performQuery(Query query) throws IOException {
        // construct from and to keys
        int indexKeyLength = getIndexKeyLength();

        byte[] fromKey = new byte[indexKeyLength];
        byte[] toKey = null;

        List<IndexFieldDefinition> fieldDefs = definition.getFields();

        Query.RangeCondition rangeCond = query.getRangeCondition();
        int offset = 0;
        boolean rangeCondSet = false;
        int usedConditionsCount = 0;
        int i = 0;
        for (; i < fieldDefs.size(); i++) {
            IndexFieldDefinition fieldDef = fieldDefs.get(i);

            Query.EqualsCondition eqCond = query.getCondition(fieldDef.getName());
            if (eqCond != null) {
                checkQueryValueType(fieldDef, eqCond.getValue());
                offset = putField(fromKey, offset, fieldDef, eqCond.getValue());
                usedConditionsCount++;
            } else if (rangeCond != null) {
                if (!rangeCond.getName().equals(fieldDef.getName())) {
                    throw new MalformedQueryException("Query defines range condition on field " + rangeCond.getName() +
                            " but has no equals condition on field " + fieldDef.getName() +
                            " which comes earlier in the index definition.");
                }

                toKey = new byte[fromKey.length];
                System.arraycopy(fromKey, 0, toKey, 0, offset);

                Object fromValue = query.getRangeCondition().getFromValue();
                Object toValue = query.getRangeCondition().getToValue();

                checkQueryValueType(fieldDef, fromValue);
                checkQueryValueType(fieldDef, toValue);

                int fromEnd = putField(fromKey, offset, fieldDef, fromValue, false);
                int toEnd = putField(toKey, offset, fieldDef, toValue, false);

                fromKey = reduceToLength(fromKey, fromEnd);
                toKey = reduceToLength(toKey, toEnd);

                rangeCondSet = true;
                usedConditionsCount++;

                break;
            } else {
                // we're done
                break;
            }
        }

        // Check if we have used all conditions defined in the query
        if (i < fieldDefs.size() && usedConditionsCount < query.getEqConditions().size() + (rangeCond != null ? 1 : 0)) {
            StringBuilder message = new StringBuilder();
            message.append("The query contains conditions on fields which either did not follow immediately on ");
            message.append("the previous equals condition or followed after a range condition on a field. The fields are: ");
            for (; i < fieldDefs.size(); i++) {
                IndexFieldDefinition fieldDef = fieldDefs.get(i);
                if (query.getCondition(fieldDef.getName()) != null) {
                    message.append(fieldDef.getName());
                } else if (rangeCond != null && rangeCond.getName().equals(fieldDef.getName())) {
                    message.append(fieldDef.getName());
                }
                message.append(" ");
            }
            throw new MalformedQueryException(message.toString());
        }

        if (!rangeCondSet) {
            // Limit the keys to the length of the used fields.
            // Note that this is different than what we do for range queries above: in the case of range queries,
            // the keys are reduced in length to the length of the from/to value of the range condition, in order
            // to support prefix queries. But for equals conditions we want exact matches, so we fill up the complete
            // field length.
            fromKey = reduceToLength(fromKey, offset);
            toKey = new byte[fromKey.length];
            System.arraycopy(fromKey, 0, toKey, 0, offset);
        }

        Scan scan = new Scan(fromKey);
        scan.setFilter(new RowFilter(CompareFilter.CompareOp.LESS_OR_EQUAL, new BinaryPrefixComparator(toKey)));
        return new ScannerQueryResult(htable.getScanner(scan), indexKeyLength);
    }

    private void checkQueryValueType(IndexFieldDefinition fieldDef, Object value) {
        if (value != null && !fieldDef.getType().supportsType(value.getClass())) {
            throw new MalformedQueryException("Query includes a condition on field " + fieldDef.getName() + " with" +
                    " a value of an incorrect type. Expected: " + fieldDef.getType().getType().getName() +
                    ", found: " + value.getClass().getName());
        }
    }

    private byte[] reduceToLength(byte[] bytes, int length) {
        if (bytes.length == length)
            return bytes;
        byte[] result = new byte[length];
        System.arraycopy(bytes, 0, result, 0, length);
        return result;
    }

}
