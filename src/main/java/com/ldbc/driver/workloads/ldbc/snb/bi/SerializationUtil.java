package com.ldbc.driver.workloads.ldbc.snb.bi;

import com.ldbc.driver.SerializingMarshallingException;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.TypeReference;

import java.io.IOException;
import java.util.List;

import static java.lang.String.format;

public class SerializationUtil
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference LIST_OF_LISTS_TYPE_REFERENCE =
            new TypeReference<List<List<Object>>>()
            {
            };
    private static final TypeReference<List<List<List<Long>>>> LIST_OF_LISTS_TYPE_REFERENCE_FOR_LONGS =
            new TypeReference<List<List<List<Long>>>>()
            {
            };


    public static synchronized List<List<Object>> marshalListOfLists( String serializedJson )
            throws SerializingMarshallingException
    {
        return marshalListOfLists( serializedJson, LIST_OF_LISTS_TYPE_REFERENCE );
    }

    public static synchronized List<List<Object>> marshalListOfListsLongs( String serializedJson )
            throws SerializingMarshallingException
    {
        return marshalListOfLists( serializedJson, LIST_OF_LISTS_TYPE_REFERENCE_FOR_LONGS );
    }

    public static synchronized List<List<Object>> marshalListOfLists( String serializedJson,
            TypeReference typeReference )
            throws SerializingMarshallingException
    {
        try
        {
            return OBJECT_MAPPER.readValue( serializedJson, typeReference );
        }
        catch ( IOException e )
        {
            throw new SerializingMarshallingException( format( "Error marshalling object\n%s", serializedJson ), e );
        }
    }

    public static synchronized String toJson( Object object ) throws SerializingMarshallingException
    {
        try
        {
            return OBJECT_MAPPER.writeValueAsString( object );
        }
        catch ( IOException e )
        {
            throw new SerializingMarshallingException( format( "Error serializing result\n%s", object.toString() ), e );
        }
    }
}
