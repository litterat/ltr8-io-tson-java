


tson-bind 
---------
This library is an implementation of some of the ideas written in [Towards Better Serialization](https://github.com/openjdk/amber-docs/blob/master/site/design-notes/towards-better-serialization.md) to create a class descriptor for use in serialization libraries and possibly other purposes. 
It is not a serialization library in itself, but a way of describing how a class should be
serialized. It is based on a discussion on the Java Amber mailing on [embedded-projection pairs](https://mail.openjdk.java.net/pipermail/amber-dev/2020-August/006445.html) and [object classification](https://mail.openjdk.java.net/pipermail/amber-dev/2020-August/006492.html). 

The library is used to generate a class descriptor which can be used to for data extraction from objects. The aim for the library is that is possible to write mappings between objects and data simply. For example the sample ArrayMapper is used as follows:

```java
// Create an instance object to be projected.
Point p1 = new Point(1,2);

// Create a context and a descriptor for the target class.
DataBindContext context = DataBindContext.builder().build();

// Create a mapper using the context.
ArrayMapper arrayMap = new ArrayMapper(context);

// Extract the values to an array. Contains [ 1, 2 ]
Object[] values = arrayMap.toData(p1);

// Create the object from the values
Point p2 = arrayMap.toObject(Point.class, values);
```

The ArrayMapper and MapMapper are both examples of how the library can be used. The ArrayMapper is implemented using MethodHandles to demonstrate how the library might be used to generate highly efficient serialization code. The MapMapper provides a procedural example to show a more simple use of the library.

## License

Litterat-bind is available under the Apache 2 License. Please see the LICENSE file for more information.


## Background & Design


The [Towards Better Serialization](https://cr.openjdk.java.net/~briangoetz/amber/serialization.html) document describes many issues
with the existing Java serialization, as well as an interesting new Java language feature to make serialization easier. The aim
of this library is to achieve the same goals without the addition of Java language features. In addition, the serialized form should be
derived from the public API of the class. This is different to the Java serialization libraries which has special capabilities to create object instances with non-existent no-args constructors and write to private final fields. Finally, it should be possible to provide
a way of describing what elements of a class needs to be serialized without resorting to using user defined methods like readObject/writeObject
that effectively encode the schema of serialization in code. By extracting the elements to be serialized from a class the structure
can be encoded and shared with readers of the data. 

The concept of the [embedded-projection](https://mail.openjdk.java.net/pipermail/amber-dev/2020-August/006445.html) pairs relates to the conversion of information between two domains; one richer than the other. A useful property of ep-pairs is that once a conversion from the richer domain to the other domain occurs the values can be mapped back and forth without further loss of information. This is a good framework from which to view
the conversion of Java's object orientated domain to the serialization or data domain. We can view Java's object orientated domain complete with methods as being "live", while the data domain without methods as being "dead". A simple definition of ep-paris, is that there is a subset S(small) for any class B(big) that we want to convert.  We want to define two functions, **project** and **embed** to go to and from S (the structure to serialize) and B (the target object).

```
project: B -> S
embed: S -> B
```

Looking closer at the two domains, Java's domain provides a very rich language of both data and methods defined by the language specification. However, the data domain can be classified simply as:

```
element: atom | tuple | array
tuple: element*
array: element[]
atom: primitive
```
    
This simple definition of the data domain fits reasonably well to a wide variety of data structures and encoding. The samples provided by the library include array and map data structures. However, this could easily apply to most serialization encodings, and text based encodings such as XML and JSON. To convert between the two domains, ep-pairs for each of tuple, array and atom must be provided by Java. If this can be achieved in a simple and consistent way then the same library can be used for many different data encoding libraries.

### Tuples

The concept of an n-element tuple with specific names and types can be represented in a wide variety of programming styles in Java. However, from a conversion point of view, we're most interested in the ways conversion functions interact with java classes. The following mechanisms were identified and the difficulty of mapping to a tuple using reflection identified:

Class that can map directly to a tuple require different styles of data extract and inject:

 * Constructor/Destructor - An n-arg constructor with n-arg destructor. The proposal suggests using this pattern, but it is not available yet.
 * Constructor/Accessors - Available, but potentially difficult to match parameters from constructor with accessors.
 * Setters/Getters - Simple, but requires no-args constructor and immutability of objects is where a lot of developers are moving.
 * Records - Similar to the immutable Constructor/Accessors with the important point that the platform provides field meta data. Java 14 only

In addition, patterns for classes that can not be directly mapped to a tuple can export and import the data:

 * Encapsulated produces data - The class has an alternative form and provides constructor and accessor for the alternate form. The alternate form being a data object.
 * Encapsulated produces "live" object - A data class that extracts and produces from a target class, with the target class not having defined a direct way to produce data.
 * Intermediate ep-pair bridge - A third class that provides both projection and embedding functions between two other classes (one Data and one live).
 * Identity - This is where the target class is also the data class. Adding this you can always call the conversion function.

By breaking up the above into two groups and including the "identity" function in the second group, it is possible to create a standard ep-pair functions:

```
toData: extract(export(object))
toObject: import(inject(data))
```

While the export and import function can easily standardised in the platform, there's still an issue with the implementation of extract and inject. Of all of the above, Java 14 Records provide the closest match to the data domain tuple. In addition, it also provides the meta data via reflection to provide a standard API. By creating a standardised extract/inject functions with corresponding reflection data we can standardise the above functions. All data classes must provide:

  * n-argument constructor - A MethodHandle which creates the object with the given values.
  * n-field components - A list of names, types and MethodHandle which returns the value of the component.
  * export/import - Two MethodHandles which perform optional export/import of the tuple into the class.

The library accomplishes this by creating reflection based wrappers around each of the of the data styles and create the DataClass. These provide a standard interface to create and access tuples classes.

 * constructor: A MethodHandle with N-arg constructor representing all fields in data object.
 * components: Fields with name and type for each element in data. A MethodHandle that returns the value of the field.
 * toObject: A MethodHandle for import function with signature (dataClass):typeClass
 * toData: A MethodHandle for export function with signature (typeClass):dataClass

 
### Atoms

An atom represents a single value represented in data. In a similar way to tuples we require a set of pe-pairs to convert from the richer expression of Java to a known set of primitives in the data domain. We've identified the following ways of representing atoms in Java

 * primitive : A primitive such as int, float, boolean, etc.
 * Object primitive : These include Integer, Float, Boolean, etc.
 * String: The Java String class.
 * Enum: Enumerated types.
 * Wrapped primitive: Any object with a single primitive constructor and accessor. e.g. UUID <---> String.
 
In a similar way to tuples, by using an identity function for primitive types, a single set of pe-pairs can be used for all conversions to/from the data domain.

```
toPrimitive: extract(object)
toObject/primitive: inject(primitive)
```

The DataClass provides the conversion of these with:

 * constructor: not defined
 * components: empty set.
 * toObject: A MethodHandle for inject function with signature (primitive):typeClass
 * toData: A MethodHandle for export function with signature (typeClass):primitive


### Arrays

To create pe-pairs generically for Java there are two different mechanisms to use. The Object[] is the simpler and can potentially have higher duplication of effort and garbage collection pressure. The alternative is to use the Collection interface which includes size(), add() and iterator().
Using the former, it is easier to create the pe-pair functions, however, a future implementation might use the later. In this implementation we use the following pe-pairs:

```
toArray: export(object)
toObject: import(array)
```

The DataClass provides the conversion of these with:

 * constructor: A MethodHandle to create the target array.
 * components: empty set.
 * toObject: A MethodHandle to convert to tart collection with signature (object[]):object
 * toData: A MethodHandle to convert to Object[]  with signature (object):Object[]


## Under development

The library is currently under heavy development so expect some rough edges. Things still to do include:

 * Collections - Collections (List,Map,Set,etc) will likely require some special handling to make them perform well.
 * Versioning - A single target class may have multiple versions and may require multiple project/embed functions.
 * Interfaces - From a serialization point of view interfaces are similar to a union and will likely require some special handling.
 * `Optional<List<T>>` (or any `Optional` wrapping a parameterized collection type) is not supported: `DefaultRecordBinder.resolveOptionalField` casts `Optional`'s actual type argument straight to `Class<?>`, which fails for a `ParameterizedType` like `List<String>` (works fine for `Optional<String>`/`Optional<SomeRecord>`, where the type argument really is a plain `Class`). Found in `ltr8-io-tson-java`'s `tson-schema` module, where a record field wanting "an empty list is absent, omit it" treatment for a `List`-typed field couldn't get it the way `Optional<SomeRecord>` fields already do.
 * Mutually-recursive record types cause infinite recursion (`StackOverflowError`), not a clean error: `DataBindContext.getDescriptor` only caches a class's descriptor *after* `resolve()` returns, and `DefaultRecordBinder` eagerly resolves every field's descriptor while building a record's own -- so two records that reference each other's type (directly, or through a field's generic argument) recurse forever the first time either is resolved. `DefaultUnionBinder` already works around exactly this for unions (its own code comment: "members are not resolved at this point as we can end up in an infinite loop... by using the actual member classes the resolution loop is broken") -- plain records have no equivalent guard. Found in `ltr8-io-tson-java`'s `tson-schema` module (`TypeRef`/`TypeArgument`, mutually recursive by design, e.g. nested generic applications like `array_ranged<array_ranged<T, N, N>, N, N>`); worked around there by keeping `TypeArgument` a sealed interface (a union breaks the cycle) instead of the more literal plain-record translation of its source shape, at the cost of a spurious type-ref tag on write. A real fix here (cycle detection, or a placeholder/lazy descriptor registered before recursing into fields) would let that workaround be removed.

## Reference

#### DataBindContext

The DataBindContext provides a library of DataClass descriptors. Each instance of a DataBindContext can have one mapping for a particular target class. This allows different communication streams to define different toData/toObject functions depending on the context of the
communications. The interface has a simple interface with three functions.

```java
// get the class descriptor for the target class.
public DataClass getDescriptor(Class<?> targetClass) throws DataBindException;
```

A DataBindException will be thrown if a descriptor could not be found or generated for the target class.

### DataClass 

The DataClass provides the descriptor of any provided class such that it can be used by a serialization library to serialize
an object instance. A DataClassField provides the components or fields of a data class. Both DataClass and DataClassField are immutable.

The DataClass has the following fields:

```java
// The target class.
private final Class<?> typeClass;

// The data class type.
private final Class<?> dataClass;

// Constructor for the data object.
private final MethodHandle constructor;

// Method handle to convert object to data object.
private final MethodHandle toData;

// Method handle to convert data object to target object.
private final MethodHandle toObject;

// All fields in the projected class. Only valid for data
private final DataClassField[] fields;

// Target class is data.
private final boolean isData;

// An atom is any value that is a single primitive value as data.
private final boolean isAtom;

// Target class is an array.
private final boolean isArray;
```

The DataClassField has the following fields:

```java
// name of the field
private final String name;

// type of the field
private final Class<?> type;

// Reference for the type.
private final DataClass dataClass;

// accessor read handle. signature: type t = object.getT();
private final MethodHandle accessor;

```

### ToData interface

The **ToData** interface adds a toData method to a class so that the developer can modify the toData form of the class.
For example, the following Location object projects LocationData converting the format of the data. The matching constructor is annotated with @Data to allow the library to find both toData and toObject functions.


```java
public class Location implements ToData<LocationData> {
   private final int latDeg;
   private final int latMin;
   private final int lonDeg;
   private final int lonMin;

   public static class LocationData {
      private final float lat;
      private final float lon;
      
      public LocationData( float lat, float lon ) {
      	this.lat = lat;
      	this.lon = lon;
      }
   }
   
   public Location(int latDeg, int latMin, int lonDeg, int lonMin) {
      this.latDeg = latDeg;
      this.latMin = latMin;
      this.lonDeg = lonDeg;
      this.lonMin = lonMin;
   }
   
   @Data
   public Location(LocationData data) {
   	  int latDeg = Math.floor(data.lat);
      int lonDeg = Math.floor(data.lon);
      return new Location( latDeg, Math.floor( (data.lat-latDeg)*60 ), 
      	                     lonDeg, Math.floor( (data.lon-lonDeg)*60 )
   }
   
   @Override
   LocationData toData() {
      return new LocationData( latDeg + (lonMin/60), lonDeg + (lonMin/60) );
   }
   
   ... accessors ...
}
```

While the target object Location stores multiple elements, the projected form for serialization uses
LocationData with two float values. 

```java
// Create an instance object to be projected.
Location loc1 = new Location(-37,14, 144, 26);

// Create a context and a descriptor for the target class.
DataBindContext context = DataBindContext.builder().build();
ArrayMapper arrayMap = new ArrayMapper(context);

// Extract the values to an array
Object[] values = arrayMap.project(loc1);

float latitude = values[0];
float longitude = values[1];

// Create the object from the values
Location loc2 = (Location) arrayMap.toObject(Location.class, values);
```

### ObjectDataBridge interface


A class can provide a class that implements the ObjectDataBridge interface.
In this case an external bridge class does the mapping to from the target class.

```java
public class Location {
   private final int latDeg;
   private final int latMin;
   private final int lonDeg;
   private final int lonMin;
   
   @Data
   public Location(int latDeg, int latMin, int lonDeg, int lonMin) {
      this.latDeg = latDeg;
      this.latMin = latMin;
      this.lonDeg = lonDeg;
      this.lonMin = lonMin;
   }
   
   ... accessors ...
}
```

The bridge code implements the conversions without needing to modify the Location class:

```java
public class LocationDataBridge implements ObjectDataBridge<LocationData,Location> {

   LocationData toData(Location location) {
     return new LocationData( latDeg + (lonMin/60), lonDeg + (lonMin/60) );
   }
   
   Location toObject(LocationData data) {
      int latDeg = Math.floor(data.lat);
      int lonDeg = Math.floor(data.lon);
      return new Location( latDeg, Math.floor( (data.lat-latDeg)*60 ), 
      	                  lonDeg, Math.floor( (data.lon-lonDeg)*60 )
   }

}
```

The bridge is registered with the context prior to converting the target class.

```java
// Create an instance object to be projected.
Location loc1 = new Location(-37,14, 144, 26);

// Create a context and a descriptor for the target class.
DataBindContext context = DataBindContext.builder().build();

// register the bridge.
context.register(Location.class, new LocationDataBridge() );

// create the mapper based on the context.
ArrayMapper arrayMap = new ArrayMapper(context);

// Extract the values to an array
Object[] values = arrayMap.project(loc1);

float latitude = values[0];
float longitude = values[1];

// Create the object from the values
Location loc2 = (Location) arrayMap.toObject(Location.class, values);
```

### Data objects

Plain Old Java Objects (POJOs) are serialized based on getter/setter pairs. In this case
a no-argument constructor must be provided. The class is annotated with @Data to identify the class
is data and can be converted.

```java
@Data
public class Point {
   private int x;
   private int y;
   
   public int getX() {
      return x;
   }
   
   public void setX(int x) {
   	  this.x = x;
   }
   
   public int getY() {
      return y;
   }
   
   public void setY(int y) {
      this.y = y;
   }
}
```

In many cases the object being serialized are simple immutable objects like the following. 
There is no projects/embeds implementations. The @Data annotation is put on the constructor to identify
how the class should be created.

```java
public class Point {
   private final int x;
   private final int y;
   
   @Data
   public Point(int x, int y) {
      this.x = x;
      this.y = y;
   }
   
   public int getX() {
      return x;
   }
   
   public int getY() {
      return y;
   }
}
```

An interesting property of Java reflection is that unless a runtime parameter is added to the command
line, the names of the constructor parameters can not be discovered. As such, it is not possible at runtime
to determine the order of x and y in the Point constructor example above. 

The bind library uses byte code
analysis to determine the ordering of the constructor parameters. The byte code analysis currently only works for
simple constructor param to field assignments, as such the Field annotation may be required. In the
example below the x and y values are modified which will currently fool the code analysis. As
such the Field annotation has been added.

```java
public class Point {
   private final int x;
   private final int y;
   
   @Data
   public Point( @Field("x") int x, @Field("y") int y) {
      this.x = x*2;
      this.y = y*3;
   }
   
   public int getX() {
      return x;
   }
   
   public int getY() {
      return y;
   }
}
```

It is also possible to mix immutable fields with mutable fields. For instance:

```java
public class Point {
   private final int x;
   private final int y;
   
   private int z;
   
   @Data
   public Point( int x,  int y) {
      this.x = x;
      this.y = y;
   }
   
   public int getX() {
      return x;
   }
   
   public int getY() {
      return y;
   }
   
   public int getZ() {
   	 return z;
   }
   
   public void setZ(int z) {
      this.z = z;
   }
}
```

The three fields will be serialized with x,y,z values. 



### Field annotation

The field annotation can be used to override the properties of a field or to assist the library in finding
the constructor/accessor pairs for a given class. In the following example the fields are renamed such
that their projected form descriptor will have the field xx, yy. This can be useful where the serialized
schema uses a different naming schema than Java.

```java
public class Point {
   private final int x;
   private final int y;
   
   @Data
   public Point(int x, int y) {
      this.x = x;
      this.y = y;
   }
   
   @Field(name="xx")
   public int getX() {
      return x;
   }
   
   @Field(name="yy")
   public int getY() {
      return y;
   }
}
```

The field annotation can be added to one of the constructor parameter, accessor, setter or class field. An error
will occur if the annotation has been added to multiple places for the same logical field.

