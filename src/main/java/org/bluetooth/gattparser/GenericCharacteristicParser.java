package org.bluetooth.gattparser;

import java.io.UnsupportedEncodingException;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bluetooth.gattparser.num.FloatingPointNumberFormatter;
import org.bluetooth.gattparser.num.RealNumberFormatter;
import org.bluetooth.gattparser.spec.Bit;
import org.bluetooth.gattparser.spec.Characteristic;
import org.bluetooth.gattparser.spec.Field;
import org.bluetooth.gattparser.spec.FieldFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GenericCharacteristicParser implements CharacteristicParser {

    private final Logger logger = LoggerFactory.getLogger(GenericCharacteristicParser.class);

    GenericCharacteristicParser() { }

    public Map<String, Object> parse(ParserContext context, String characteristicUUID, byte[] raw)
            throws CharacteristicFormatException {
        Map<String, Object> result = new HashMap<>();
        Characteristic characteristic = context.getSpecificationReader().getCharacteristic(characteristicUUID);

        if (!characteristic.isValidForRead()) {
            logger.error("Characteristic cannot be parsed: \"{}\".", characteristic.getName());
            throw new CharacteristicFormatException("Characteristic cannot be parsed: \"" +
                    characteristic.getName() + "\".");
        }

        int offset = 0;
        Set<String> requires = getFlags(context, characteristic, raw);
        for (Field field : characteristic.getValue().getFields()) {
            if (field.getName().equalsIgnoreCase("flags")) {
                // skipping flags field
                continue;
            }
            List<String> requirements = field.getRequirements();
            if (requirements != null && !requirements.isEmpty() && !requires.containsAll(requirements)) {
                // skipping field as per requirement in the Flags field
                continue;
            }

            FieldFormat fieldFormat = field.getFormat();
            Object value = parse(context, field, raw, offset);
            result.put(field.getName(), value);
            if (fieldFormat.getSize() == FieldFormat.FULL_SIZE) {
                // full size field, e.g. a string
                break;
            }
            offset += field.getFormat().getSize();
        }
        return result;
    }

    int[] parseFlags(ParserContext context, Field flagsField, byte[] raw) {
        BitSet bitSet = BitSet.valueOf(raw).get(0, flagsField.getFormat().getSize());
        List<Bit> bits = flagsField.getBitField().getBits();
        int[] flags = new int[bits.size()];
        int offset = 0;
        for (int i = 0; i < bits.size(); i++) {
            int size = bits.get(i).getSize();
            flags[i] = context.getRealNumberFormatter().deserializeInteger(
                    bitSet.get(offset, offset + size), size, false);
            offset += size;
        }
        return flags;
    }

    Set<String> getFlags(ParserContext context, Characteristic characteristic, byte[] raw) {
        Set<String> flags = new HashSet<>();
        Field flagsField = characteristic.getValue().getFlags();
        if (flagsField != null && flagsField.getBitField() != null) {
            int[] values = parseFlags(context, flagsField, raw);
            int i = 0;
            for (Bit bit : flagsField.getBitField().getBits()) {
                String value = bit.getRequires((byte) values[i++]);
                if (value != null) {
                    flags.add(value);
                }
            }
        }
        return flags;
    }

    private Object parse(ParserContext context, Field field, byte[] raw, int offset) {
        FieldFormat fieldFormat = field.getFormat();
        int size = fieldFormat.getSize();
        Integer exponent = field.getDecimalExponent();
        //TODO handle exponent
        switch (fieldFormat.getType()) {
            case BOOLEAN: return raw[offset] == 1;
            case UINT: return deserializeReal(context, raw, offset, size, false);
            case SINT: return deserializeReal(context, raw, offset, size, true);
            case FLOAT: return deserializeFloat(context, raw, offset, size);
            case UTF8S: return deserializeString(raw, "UTF-8");
            case UTF16S: return deserializeString(raw, "UTF-16");
            default:
                throw new IllegalStateException("Unsupported field format: " + fieldFormat.getType());
        }
    }

    private Object deserializeReal(ParserContext context, byte[] raw, int offset, int size, boolean signed) {
        RealNumberFormatter realNumberFormatter = context.getRealNumberFormatter();
        if ((signed && size < 32) || (!signed && size <= 32)) {
            return realNumberFormatter.deserializeInteger(BitSet.valueOf(raw).get(offset, raw.length), size, signed);
        } else if ((signed && size < 64) || (!signed && size <= 64)) {
            return realNumberFormatter.deserializeLong(BitSet.valueOf(raw).get(offset, raw.length), size, signed);
        } else {
            return realNumberFormatter.deserializeBigInteger(BitSet.valueOf(raw).get(offset, raw.length), size, signed);
        }
    }

    private Object deserializeFloat(ParserContext context, byte[] raw, int offset, int size) {
        FloatingPointNumberFormatter floatingPointNumberFormatter = context.getFloatingPointNumberFormatter();
        if (size == 16) {
            return floatingPointNumberFormatter.deserializeSFloat(BitSet.valueOf(raw).get(offset, raw.length));
        } else if (size == 32) {
            return floatingPointNumberFormatter.deserializeFloat(BitSet.valueOf(raw).get(offset, raw.length));
        } else if (size == 64) {
            return floatingPointNumberFormatter.deserializeDouble(BitSet.valueOf(raw).get(offset, raw.length));
        } else {
            throw new IllegalStateException("Unknown bit size for float numbers: " + size);
        }
    }

    private String deserializeString(byte[] raw, String encoding) {
        try {
            return new String(raw, encoding);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

}
