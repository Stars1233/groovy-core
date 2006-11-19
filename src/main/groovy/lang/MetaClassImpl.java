/*
$Id$

Copyright 2003 (C) James Strachan and Bob Mcwhirter. All Rights Reserved.

Redistribution and use of this software and associated documentation
("Software"), with or without modification, are permitted provided
that the following conditions are met:

1. Redistributions of source code must retain copyright
   statements and notices.  Redistributions must also contain a
   copy of this document.

2. Redistributions in binary form must reproduce the
   above copyright notice, this list of conditions and the
   following disclaimer in the documentation and/or other
   materials provided with the distribution.

3. The name "groovy" must not be used to endorse or promote
   products derived from this Software without prior written
   permission of The Codehaus.  For written permission,
   please contact info@codehaus.org.

4. Products derived from this Software may not be called "groovy"
   nor may "groovy" appear in their names without prior written
   permission of The Codehaus. "groovy" is a registered
   trademark of The Codehaus.

5. Due credit should be given to The Codehaus -
   http://groovy.codehaus.org/

THIS SOFTWARE IS PROVIDED BY THE CODEHAUS AND CONTRIBUTORS
``AS IS'' AND ANY EXPRESSED OR IMPLIED WARRANTIES, INCLUDING, BUT
NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL
THE CODEHAUS OR ITS CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
OF THE POSSIBILITY OF SUCH DAMAGE.

*/
package groovy.lang;

import java.beans.BeanInfo;
import java.beans.EventSetDescriptor;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.classgen.BytecodeHelper;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.runtime.CurriedClosure;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;
import org.codehaus.groovy.runtime.DefaultMethodKey;
import org.codehaus.groovy.runtime.GroovyCategorySupport;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.codehaus.groovy.runtime.MetaClassHelper;
import org.codehaus.groovy.runtime.MethodClosure;
import org.codehaus.groovy.runtime.MethodKey;
import org.codehaus.groovy.runtime.NewInstanceMetaMethod;
import org.codehaus.groovy.runtime.NewStaticMetaMethod;
import org.codehaus.groovy.runtime.ReflectionMetaMethod;
import org.codehaus.groovy.runtime.Reflector;
import org.codehaus.groovy.runtime.TransformMetaMethod;
import org.codehaus.groovy.runtime.wrappers.Wrapper;
import org.objectweb.asm.ClassVisitor;

/**
* Allows methods to be dynamically added to existing classes at runtime
*
* @author <a href="mailto:james@coredevelopers.net">James Strachan</a>
* @author Guillaume Laforge
* @author Jochen Theodorou
* @version $Revision$
* @see groovy.lang.MetaClass
*/
public class MetaClassImpl extends MetaClass {

   protected MetaClassRegistry registry;
   private ClassNode classNode;
   private Map classMethodIndex = new HashMap();
   private Map classMethodIndexForSuper;
   private Map classStaticMethodIndex = new HashMap();
   //private Map propertyDescriptors = Collections.synchronizedMap(new HashMap());
   private Map propertyMap = Collections.synchronizedMap(new HashMap());
   private Map listeners = new HashMap();
   private Map methodCache = Collections.synchronizedMap(new HashMap());
   private Map staticMethodCache = Collections.synchronizedMap(new HashMap());
   private MetaMethod genericGetMethod;
   private MetaMethod genericSetMethod;
   private List constructors;
   private List allMethods = new ArrayList();
   private List interfaceMethods;
   private Reflector reflector;
   private boolean initialised;
   // we only need one of these that can be reused over and over.
   private MetaProperty arrayLengthProperty = new MetaArrayLengthProperty();
   private final static MetaMethod AMBIGOUS_LISTENER_METHOD = new MetaMethod(null,null,new Class[]{},null,0);
   private static final Object[] EMPTY_ARGUMENTS = {};
   private BeanInfo info;
   
   public MetaClassImpl(MetaClassRegistry registry, final Class theClass) throws IntrospectionException {
       super(theClass);
       this.registry = registry;

       constructors = (List) AccessController.doPrivileged(new  PrivilegedAction() {
               public Object run() {
                   return Arrays.asList (theClass.getDeclaredConstructors());
               }
           });
       
       //     introspect
       try {
           info =(BeanInfo) AccessController.doPrivileged(new PrivilegedExceptionAction() {
               public Object run() throws IntrospectionException {
                   return Introspector.getBeanInfo(theClass);
               }
           });
       } catch (PrivilegedActionException pae) {
           if (pae.getException() instanceof IntrospectionException) {
               throw (IntrospectionException) pae.getException();
           } else {
               throw new RuntimeException(pae.getException());
           }
       }
   }

   private void fillMethodIndex() {
       LinkedList superClasses = new LinkedList();
       for (Class c = theClass; c!= null; c = c.getSuperclass()) {
           superClasses.addFirst(c);
       }
       if (theClass.isArray() && theClass!=Object[].class && !theClass.getComponentType().isPrimitive()) {
           superClasses.addFirst(Object[].class);
       }
       
       // let's add all the base class methods
       for (Iterator iter = superClasses.iterator(); iter.hasNext();) {
           Class c = (Class) iter.next();
           addMethods(c);
       }
       
       inheritMethods(superClasses,classMethodIndex);
       copyClassMethodIndexForSuper();
       
       Set interfaces = new HashSet();
       makeInterfaceSet(theClass,interfaces); 
       
       inheritInterfaceMethods(interfaces);
       connectMultimethods(superClasses);
       populateInterfaces(interfaces);
       
       replaceWithMOPCalls();
   }
   
   private void replaceWithMOPCalls() {
       // no MOP methods if not a child of GroovyObject
       if (!GroovyObject.class.isAssignableFrom(theClass)) return;
       
       Map mainClassMethodIndex = (Map) classMethodIndex.get(theClass);
       
       // replace all calls for super with the correct MOP method
       for (Iterator iter = classMethodIndexForSuper.entrySet().iterator(); iter.hasNext();) {
           Map.Entry classEntry = (Map.Entry) iter.next();
           if (classEntry.getKey() == theClass) continue;
           Map methodIndex = (Map) classEntry.getValue();
           for (Iterator iterator = methodIndex.entrySet().iterator(); iterator.hasNext();) {
               Map.Entry nameEntry = (Map.Entry) iterator.next();
               List oldList = (List) nameEntry.getValue();
               List newList = new ArrayList(oldList.size());
               for (Iterator methodIter = oldList.iterator(); methodIter.hasNext();) {
                   MetaMethod method = (MetaMethod) methodIter.next();
                   String mopName = getMOPMethodName(method.getDeclaringClass(), method.getName(),false);
                   List matches = (List) mainClassMethodIndex.get(mopName);
                   if (matches==null) {
                       newList.add(method);
                       continue;
                   }
                   matches = new ArrayList(matches);
                   MetaMethod matchingMethod = removeMatchingMethod(matches,method);
                   if (matchingMethod==null) {
                       newList.add(method);
                       continue;
                   } else {
                       newList.add(matchingMethod);
                   }
               }
               nameEntry.setValue(newList);
           }               
       }
       // replace all calls for this with the correct MOP method
       for (Iterator iter = classMethodIndex.entrySet().iterator(); iter.hasNext();) {
           Map.Entry classEntry = (Map.Entry) iter.next();
           Class clazz = (Class) classEntry.getKey();
           Map methodIndex = (Map) classEntry.getValue();
           for (Iterator iterator = methodIndex.entrySet().iterator(); iterator.hasNext();) {
               Map.Entry nameEntry = (Map.Entry) iterator.next();
               List oldList = (List) nameEntry.getValue();
               List newList = new ArrayList(oldList.size());
               for (Iterator methodIter = oldList.iterator(); methodIter.hasNext();) {
                   MetaMethod method = (MetaMethod) methodIter.next();
                   String mopName = getMOPMethodName(clazz, method.getName(),true);
                   List matches = (List) mainClassMethodIndex.get(mopName);
                   if (matches==null) {
                       newList.add(method);
                       continue;
                   }
                   matches = new ArrayList(matches);
                   MetaMethod matchingMethod = removeMatchingMethod(matches,method);
                   if (matchingMethod==null) {
                       newList.add(method);
                       continue;
                   } else {
                       newList.add(matchingMethod);
                   }
               }
               nameEntry.setValue(newList);
           }               
       }

   }
   
   private String getMOPMethodName(Class declaringClass, String name, boolean useThis) {
       int distance = 0;
       for (;declaringClass!=null; declaringClass=declaringClass.getSuperclass()) {
           distance++;
       }
       return (useThis?"this":"super")+"$"+distance+"$"+name;
   }
   
   private void copyClassMethodIndexForSuper() {
       classMethodIndexForSuper = new HashMap(classMethodIndex.size());
       for (Iterator iter = classMethodIndex.entrySet().iterator(); iter.hasNext();) {
           Map.Entry cmiEntry = (Map.Entry) iter.next();
           Map methodIndex = (Map) cmiEntry.getValue();
           Map copy = new HashMap (methodIndex.size());
           for (Iterator iterator = methodIndex.entrySet().iterator(); iterator.hasNext();) {
               Map.Entry mEntry = (Map.Entry) iterator.next();
               copy.put(mEntry.getKey(), new ArrayList((List) mEntry.getValue()));
           }
           classMethodIndexForSuper.put(cmiEntry.getKey(),copy);
       } 
   }
   
   private void inheritInterfaceMethods(Set interfaces) {
       // add methods declared by DGM for interfaces
       List methods = registry.getInstanceMethods();
       for (Iterator iter = methods.iterator(); iter.hasNext();) {
           Method element = (Method) iter.next();
           Class dgmClass = element.getParameterTypes()[0]; 
           if (!interfaces.contains(dgmClass)) continue;
           NewInstanceMetaMethod method = new NewInstanceMetaMethod(createMetaMethod(element));
           if (! newGroovyMethodsList.contains(method)){
               newGroovyMethodsList.add(method);
           }
           Map methodIndex = (Map) classMethodIndex.get(theClass);
           List list = (List) methodIndex.get(method.getName());
           if (list == null) {
               list = new ArrayList();
               methodIndex.put(method.getName(), list);
               list.add(method);
           } else {
               addMethodToList(list,method);
           }
       }
       methods = registry.getStaticMethods();
       for (Iterator iter = methods.iterator(); iter.hasNext();) {
           Method element = (Method) iter.next();
           Class dgmClass = element.getParameterTypes()[0]; 
           if (!interfaces.contains(dgmClass)) continue;
           addNewStaticMethod(element);
       }
   }
   
   private void populateInterfaces(Set interfaces){
       Map currentIndex = (Map) classMethodIndex.get(theClass);
       Map index = new HashMap();
       copyNonPrivateMethods(currentIndex,index);
       for (Iterator iter = interfaces.iterator(); iter.hasNext();) {
           Class iClass = (Class) iter.next();
           Map methodIndex = (Map) classMethodIndex.get(iClass);
           if (methodIndex==null || methodIndex.size()==0) {
               classMethodIndex.put(iClass,index);
               continue;
           }
           copyNonPrivateMethods(currentIndex,methodIndex);
       }
   }
   
   private static void makeInterfaceSet(Class c, Set s) {
       if (c==null) return;
       Class[] interfaces = c.getInterfaces();
       for (int i = 0; i < interfaces.length; i++) {
           if (!s.contains(interfaces[i])) {
               s.add(interfaces[i]);
               makeInterfaceSet(interfaces[i],s);
           }
       }
       makeInterfaceSet(c.getSuperclass(),s);
   }
   
   private void copyNonPrivateMethods(Map from, Map to) {
       for (Iterator iterator = from.entrySet().iterator(); iterator.hasNext();) {
           Map.Entry element = (Map.Entry) iterator.next();
           List oldList = (List) element.getValue();
           List newList = (List) to.get(element.getKey());
           if (newList==null) {
               to.put(element.getKey(),new ArrayList(oldList));
           } else {
               addNonPrivateMethods(newList,oldList);
           }
       }
   }
   
   private void connectMultimethods(List superClasses){
       superClasses = DefaultGroovyMethods.reverse(superClasses);
       Map last = null;
       for (Iterator iter = superClasses.iterator(); iter.hasNext();) {
           Class c = (Class) iter.next();
           Map methodIndex = (Map) classMethodIndex.get(c);
           if (methodIndex==last) continue;
           if (last!=null) copyNonPrivateMethods(last,methodIndex);
           last = methodIndex;
       }
   }
   
   private void inheritMethods(Collection superClasses, Map classMethodIndex){
       Map last = null;
       for (Iterator iter = superClasses.iterator(); iter.hasNext();) {
           Class c = (Class) iter.next();
           Map methodIndex = (Map) classMethodIndex.get(c);
           if (last!=null) {
               if (methodIndex.size()==0) {
                   classMethodIndex.put(c,last);
                   continue;
               }
               copyNonPrivateMethods(last,methodIndex);
           }
           last = methodIndex;
       }
   }

   private void addNonPrivateMethods(List newList, List oldList) {
       for (Iterator iter = oldList.iterator(); iter.hasNext();) {
           MetaMethod element = (MetaMethod) iter.next();
           if (element.isPrivate()) continue;
           addMethodToList(newList,element);
       }
   }

/**
    * @return all the normal instance methods avaiable on this class for the
    *         given name
    */
   private List getMethods(Class sender, String name, boolean isCallToSuper) {
       Map methodIndex;
       if (isCallToSuper) {
           methodIndex = (Map) classMethodIndexForSuper.get(sender);
       } else {
           methodIndex = (Map) classMethodIndex.get(sender);
       }   
       List answer;
       if (methodIndex!=null) {
           answer = (List) methodIndex.get(name);
           if (answer == null) answer = Collections.EMPTY_LIST;
       } else {
           answer = Collections.EMPTY_LIST;
       }
       
       if (!isCallToSuper) {
           List used = GroovyCategorySupport.getCategoryMethods(sender, name);
           if (used != null) {
               used.addAll(answer);
               answer = used;
           }
       }
       return answer;
   }

   /**
    * @return all the normal static methods avaiable on this class for the
    *         given name
    */
   private List getStaticMethods(Class sender, String name) {
       Map methodIndex = (Map) classStaticMethodIndex.get(sender);
       if (methodIndex == null) return Collections.EMPTY_LIST;
       List answer = (List) methodIndex.get(name);
       if (answer == null) return Collections.EMPTY_LIST;
       return answer;
   }

   public void addNewInstanceMethod(Method method) {
       if (initialised) {
           throw new RuntimeException("Already initialized, cannot add new method: " + method);
       }
       else {
           NewInstanceMetaMethod newMethod = new NewInstanceMetaMethod(createMetaMethod(method));
           if (! newGroovyMethodsList.contains(newMethod)){
               newGroovyMethodsList.add(newMethod);
               addMethod(newMethod);
           }
       }
   }

   public void addNewStaticMethod(Method method) {
       if (initialised) {
           throw new RuntimeException("Already initialized, cannot add new method: " + method);
       }
       else {
           NewStaticMetaMethod newMethod = new NewStaticMetaMethod(createMetaMethod(method));
           if (! newGroovyMethodsList.contains(newMethod)){
               newGroovyMethodsList.add(newMethod);
               addMethod(newMethod);
           }
       }
   }

   private void unwrap(Object[] arguments) {
       //
       // Temp code to ignore wrapped parameters
       // The New MOP will deal with these properly
       //
       for (int i = 0; i != arguments.length; i++) {
        if (arguments[i] instanceof Wrapper) {
          arguments[i] = ((Wrapper)arguments[i]).unwrap();
        }
       }       
   }
   
   
   /**
    * Invokes the given method on the object.
    *
    */
   public Object invokeMethod(Object object, String methodName, Object[] originalArguments) {
       return invokeMethod(theClass,object,methodName,originalArguments,false);
   }
   
   
   /**
    * Invokes the given method on the object.
    *
    */
   public Object invokeMethod(Class sender, Object object, String methodName, Object[] originalArguments, boolean isCallToSuper) {
       checkInitalised();
       if (object == null) {
           throw new NullPointerException("Cannot invoke method: " + methodName + " on null object");
       }              
       if (log.isLoggable(Level.FINER)){
           MetaClassHelper.logMethodCall(object, methodName, originalArguments);
       }
       Object[] arguments = originalArguments;
       if (arguments==null) arguments = EMPTY_ARGUMENTS;
       Class[] argClasses = MetaClassHelper.convertToTypeArray(arguments);
       unwrap(arguments);
       
       MetaMethod method = retrieveMethod(sender, methodName, argClasses, isCallToSuper);
       
       if (method==null && arguments.length==1 && arguments[0] instanceof List) {
           Object[] newArguments = ((List) arguments[0]).toArray();
           Class[] newArgClasses = MetaClassHelper.convertToTypeArray(newArguments);
           method = retrieveMethod(sender, methodName, newArgClasses, isCallToSuper);
           if (method!=null) {
               MethodKey methodKey = new DefaultMethodKey(sender, methodName, argClasses);
               method = new TransformMetaMethod(method) {
                   public Object invoke(Object object, Object[] arguments) throws Exception {
                       Object firstArgument = arguments[0];
                       List list = (List) firstArgument;
                       arguments = list.toArray();
                       return super.invoke(object, arguments);
                   }
               };
               methodCache.put(methodKey, method);
               return invokeMethod(sender,object,methodName, originalArguments, isCallToSuper);
           }
       }

       boolean isClosure = object instanceof Closure;
       if (isClosure) {
           Closure closure = (Closure) object;
           Object delegate = closure.getDelegate();
           Object owner = closure.getOwner();

           if ("call".equals(methodName) || "doCall".equals(methodName)) {
               if (object.getClass()==MethodClosure.class) {
                   MethodClosure mc = (MethodClosure) object;
                   methodName = mc.getMethod();
                   MetaClass ownerMetaClass = registry.getMetaClass(owner.getClass());
                   return ownerMetaClass.invokeMethod(owner.getClass(),owner,methodName,arguments,false);
               } else if (object.getClass()==CurriedClosure.class) {
                   CurriedClosure cc = (CurriedClosure) object;
                   // change the arguments for an uncurried call
                   arguments = cc.getUncurriedArguments(arguments);
                   MetaClass ownerMetaClass = registry.getMetaClass(owner.getClass());
                   return ownerMetaClass.invokeMethod(owner,methodName,arguments);
               }
           } else if ("curry".equals(methodName)) {
               return closure.curry(arguments);
           }

           if (method==null && owner!=closure) {
               MetaClass ownerMetaClass = registry.getMetaClass(owner.getClass());
               method = ownerMetaClass.retrieveMethod(methodName,argClasses);
               if (method!=null) return ownerMetaClass.invokeMethod(owner,methodName,originalArguments);
           }
           if (method==null && delegate!=closure && delegate!=null) {
               MetaClass delegateMetaClass = registry.getMetaClass(delegate.getClass());
               method = delegateMetaClass.retrieveMethod(methodName,argClasses);
               if (method!=null) return delegateMetaClass.invokeMethod(delegate,methodName,originalArguments);
           }
           if (method==null) {
               // still no methods found, test if delegate or owner are GroovyObjects
               // and invoke the method on them if so.
               MissingMethodException last = null;
               if (owner!=closure && (owner instanceof GroovyObject)) {
                   try {
                       GroovyObject go = (GroovyObject) owner;
                       return go.invokeMethod(methodName,originalArguments);
                   } catch (MissingMethodException mme) {
                       if (last==null) last = mme;
                   }
               }
               if (delegate!=closure && (delegate instanceof GroovyObject)) {
                   try {
                       GroovyObject go = (GroovyObject) delegate;
                       return go.invokeMethod(methodName,originalArguments);
                   } catch (MissingMethodException mme) {
                       last = mme;
                   }
               }
               if (last!=null) throw last;
           }

       }

       if (method != null) {
           return MetaClassHelper.doMethodInvoke(object, method, arguments);
       } else {
           // if no method was found, try to find a closure defined as a field of the class and run it
           try {
               Object value = this.getProperty(object, methodName);
               if (value instanceof Closure) {  // This test ensures that value != this If you ever change this ensure that value != this
                   Closure closure = (Closure) value;
                   MetaClass delegateMetaClass = closure.getMetaClass();
                   return delegateMetaClass.invokeMethod(closure.getClass(),closure,"doCall",originalArguments,false);
               }
           } catch (MissingPropertyException mpe) {}

           throw new MissingMethodException(methodName, theClass, originalArguments, false);
       }
   }

   /**
    * @see groovy.lang.MetaClass#retrieveMethod(java.lang.Object, java.lang.String, java.lang.Object[])
    * @deprecated
    */
   public MetaMethod retrieveMethod(Object owner, String methodName, Object[] arguments) {
       return retrieveMethod(methodName,MetaClassHelper.convertToTypeArray(arguments));
   }
   
   /**
    * @see groovy.lang.MetaClass#retrieveMethod(java.lang.Object, java.lang.String, java.lang.Object[])
    * @deprecated
    */
   public MetaMethod retrieveMethod(String methodName, Class[] arguments) {
       return retrieveMethod(theClass,methodName,arguments,false);
   }
   
   public MetaMethod retrieveMethod(Class sender, String methodName, Class[] arguments, boolean isCallToSuper) {
       // lets try use the cache to find the method
       //TODO: add isSuperCall to key
       MethodKey methodKey = new DefaultMethodKey(sender, methodName, arguments);
       MetaMethod method = (MetaMethod) methodCache.get(methodKey);
       if (method == null) {
           method = pickMethod(sender, methodName, arguments, isCallToSuper);
           if (method != null && method.isCacheable()) {
               methodCache.put(methodKey, method);
           }
       }
       return method;
   }

   public Constructor retrieveConstructor(Class[] arguments) {
       Constructor constructor = (Constructor) chooseMethod("<init>", constructors, arguments, false);
       if (constructor != null) {
           return constructor;
       }
       else {
           constructor = (Constructor) chooseMethod("<init>", constructors, arguments, true);
           if (constructor != null) {
               return constructor;
           }
       }
       return null;
   }

   public MetaMethod retrieveStaticMethod(String methodName, Class[] arguments) {
       //TODO: implement!
       MethodKey methodKey = new DefaultMethodKey(theClass, methodName, arguments);
       MetaMethod method = (MetaMethod) staticMethodCache.get(methodKey);
       if (method == null) {
           method = pickStaticMethod(theClass,methodName, arguments);
           if (method != null) {
               staticMethodCache.put(methodKey, method);
           }
       }
       return method;
   }
   /**
    * Picks which method to invoke for the given object, method name and arguments
    * @deprecated
    */
   public MetaMethod pickMethod(Object object, String methodName, Object[] arguments) {
       return pickMethod(methodName,MetaClassHelper.convertToTypeArray(arguments));
   }
   
   public MetaMethod pickMethod(Class sender, String methodName, Class[] arguments, boolean isCallToSuper) {
       MetaMethod method = null;
       List methods = getMethods(sender,methodName,isCallToSuper);
       if (methods!=null && !methods.isEmpty()) {
           method = (MetaMethod) chooseMethod(methodName, methods, arguments, false);
       }
       return method;
   }
   
   /**
    * pick a method in a strict manner, i.e., without reinterpreting the first List argument.
    * this method is used only by ClassGenerator for static binding
    * @param methodName
    * @param arguments
    * @deprecated
    */
   public MetaMethod pickMethod(String methodName, Class[] arguments) {
       return pickMethod(theClass,methodName,arguments,false);
   }

   public Object invokeStaticMethod(Object object, String methodName, Object[] arguments) {
       checkInitalised();
       if (log.isLoggable(Level.FINER)){
           MetaClassHelper.logMethodCall(object, methodName, arguments);
       }
       
       Class sender = object.getClass();
       if (object instanceof Class) sender = (Class) object;
       if (sender!=theClass) {
           MetaClass mc = registry.getMetaClass(sender);
           return mc.invokeStaticMethod(sender,methodName,arguments);
       }
       if (sender==Class.class) {
           return invokeMethod(object,methodName,arguments);
       }
       
       if (arguments==null) arguments = EMPTY_ARGUMENTS;
       Class[] argClasses = MetaClassHelper.convertToTypeArray(arguments);
       unwrap(arguments);
       
       // lets try use the cache to find the method
       MethodKey methodKey = new DefaultMethodKey(sender, methodName, argClasses);
       MetaMethod method = (MetaMethod) staticMethodCache.get(methodKey);
       if (method == null) {
           method = pickStaticMethod(sender, methodName, argClasses);
           if (method != null) {
               staticMethodCache.put(methodKey.createCopy(), method);
           }
       }

       if (method != null) {
           return MetaClassHelper.doMethodInvoke(object, method, arguments);
       }

       throw new MissingMethodException(methodName, sender, arguments, true);
   }
   
   private MetaMethod pickStaticMethod(Class sender, String methodName, Class[] arguments) {
       MetaMethod method = null;
       List methods = getStaticMethods(sender,methodName);

       if (!methods.isEmpty()) {
           method = (MetaMethod) chooseMethod(methodName, methods, arguments, false);
       }
       if (method == null && theClass != Class.class) {
           MetaClass classMetaClass = registry.getMetaClass(Class.class);
           method = classMetaClass.pickMethod(methodName, arguments);
       }
       if (method == null) {
           method = (MetaMethod) chooseMethod(methodName, methods, MetaClassHelper.convertToTypeArray(arguments), true);
       }
       return method;
   }

   public Object invokeConstructor(Object[] arguments) {
       return invokeConstructor(theClass,arguments,false);
   }

   public int selectConstructorAndTransformArguments(int numberOfCosntructors, Object[] arguments) {
       //TODO: that is just a quick prototype, not the real thing!
       if (numberOfCosntructors != constructors.size()) {
           throw new IncompatibleClassChangeError("the number of constructors during runtime and compile time for "+
               this.theClass.getName()+" do not match. Expected "+numberOfCosntructors+" but got "+constructors.size());
       }
       
       if (arguments==null) arguments = EMPTY_ARGUMENTS;
       Class[] argClasses = MetaClassHelper.convertToTypeArray(arguments);
       unwrap(arguments);       
       Constructor constructor = (Constructor) chooseMethod("<init>", constructors, argClasses, false);
       if (constructor == null) {
           constructor = (Constructor) chooseMethod("<init>", constructors, argClasses, true);
       }
       if (constructor==null) {
           throw new GroovyRuntimeException(
                   "Could not find matching constructor for: "
                       + theClass.getName()
                       + "("+InvokerHelper.toTypeString(arguments)+")");
       }
       List l = new ArrayList(constructors);
       Comparator comp = new Comparator() {
           public int compare(Object arg0, Object arg1) {
               Constructor c0 = (Constructor) arg0;
               Constructor c1 = (Constructor) arg1;
               String descriptor0 = BytecodeHelper.getMethodDescriptor(Void.TYPE, c0.getParameterTypes()); 
               String descriptor1 = BytecodeHelper.getMethodDescriptor(Void.TYPE, c1.getParameterTypes());
               return descriptor0.compareTo(descriptor1);
           }            
       };
       Collections.sort(l,comp);
       int found = -1;
       for (int i=0; i<l.size(); i++) {
           if (l.get(i)!=constructor) continue;
           found = i;
           break;
       }
       // NOTE: must be changed to "1 |" if constructor was vargs
       int ret = 0 | (found << 8);
       return ret;
   }
   
   private void checkInitalised() {
       if (!initialised) 
           throw new IllegalStateException(
                   "initialise must be called for meta " +
                   "class of "+ theClass + 
                   "("+this.getClass() + ") " +
                   "to complete initialisation process " +
                   "before any invocation or field/property " +
                   "access can be done");
   }
   
   private Object invokeConstructor(Class at, Object[] arguments, boolean setAccessible) {
       checkInitalised();
       if (arguments==null) arguments = EMPTY_ARGUMENTS;
       Class[] argClasses = MetaClassHelper.convertToTypeArray(arguments);
       unwrap(arguments);       
       Constructor constructor = (Constructor) chooseMethod("<init>", constructors, argClasses, false);
       if (constructor != null) {
           return doConstructorInvoke(at, constructor, arguments, true);
       }
       else {
           constructor = (Constructor) chooseMethod("<init>", constructors, argClasses, true);
           if (constructor != null) {
               return doConstructorInvoke(at, constructor, arguments, true);
           }
       }

       if (arguments.length == 1) {
           Object firstArgument = arguments[0];
           if (firstArgument instanceof Map) {
               constructor = (Constructor) chooseMethod("<init>", constructors, MetaClassHelper.EMPTY_TYPE_ARRAY, false);
               if (constructor != null) {
                   Object bean = doConstructorInvoke(at, constructor, MetaClassHelper.EMPTY_ARRAY, true);
                   setProperties(bean, ((Map) firstArgument));
                   return bean;
               }
           }
       }
       throw new GroovyRuntimeException(
                   "Could not find matching constructor for: "
                       + theClass.getName()
                       + "("+InvokerHelper.toTypeString(arguments)+")");
   }
   
   public Object invokeConstructorAt(Class at, Object[] arguments) {
       return invokeConstructor(at,arguments,true);
   }

   /**
    * Sets a number of bean properties from the given Map where the keys are
    * the String names of properties and the values are the values of the
    * properties to set
    */
   public void setProperties(Object bean, Map map) {
       checkInitalised();
       for (Iterator iter = map.entrySet().iterator(); iter.hasNext();) {
           Map.Entry entry = (Map.Entry) iter.next();
           String key = entry.getKey().toString();

           Object value = entry.getValue();
           try {
               setProperty(bean, key, value);
           }
           catch (GroovyRuntimeException e) {
               // lets ignore missing properties
               /** todo should replace this code with a getMetaProperty(key) != null check
                i.e. don't try and set a non-existent property
                */
           }
       }
   }

   /**
    * @return the given property's value on the object
    */
   public Object getProperty(final Object object, final String property) {
       checkInitalised();
       // look for the property in our map
       MetaProperty mp = (MetaProperty) propertyMap.get(property);
       if (mp != null) {
           try {
               //System.out.println("we found a metaproperty for " + theClass.getName() +
               //  "." + property);
               // delegate the get operation to the metaproperty
               return mp.getProperty(object);
           }
           catch(Exception e) {
               throw new GroovyRuntimeException("Cannot read property: " + property);
           }
       }

       if (genericGetMethod == null) {
           // Make sure there isn't a generic method in the "use" cases
           //TODO: implement!
           List possibleGenericMethods = getMethods(theClass,"get",false);
           if (possibleGenericMethods != null) {
               for (Iterator i = possibleGenericMethods.iterator(); i.hasNext(); ) {
                   MetaMethod mmethod = (MetaMethod) i.next();
                   Class[] paramTypes = mmethod.getParameterTypes();
                   if (paramTypes.length == 1 && paramTypes[0] == String.class) {
                       Object[] arguments = {property};
                       Object answer = MetaClassHelper.doMethodInvoke(object, mmethod, arguments);
                       return answer;
                   }
               }
           }
       }
       else {
           Object[] arguments = { property };
           Object answer = MetaClassHelper.doMethodInvoke(object, genericGetMethod, arguments);
           // jes bug? a property retrieved via a generic get() can't have a null value?
           if (answer != null) {
               return answer;
           }
       }

       // lets try invoke a static getter method
       // this case is for protected fields. I wish there was a better way...
       Exception lastException = null;
       try {
           if ( !(object instanceof Class) ) {
               MetaMethod method = findGetter(object, "get" + MetaClassHelper.capitalize(property));
               if (method != null) {
                  return MetaClassHelper.doMethodInvoke(object, method, MetaClassHelper.EMPTY_ARRAY);
              }
          }
       }
       catch (GroovyRuntimeException e) {
           lastException = e;
       }

       /** todo or are we an extensible groovy class? */
       if (genericGetMethod != null) {
           return null;
       }
       else {
           /** todo these special cases should be special MetaClasses maybe */
           if (object instanceof Class) {
               // lets try a static field
               return getStaticProperty((Class) object, property);
           }
           if (object instanceof Collection) {
               return DefaultGroovyMethods.getAt((Collection) object, property);
           }
           if (object instanceof Object[]) {
               return DefaultGroovyMethods.getAt(Arrays.asList((Object[]) object), property);
           }
           if (object instanceof Object) {
               try {
                   return getAttribute(object,property);
               } catch (MissingFieldException mfe) {
                   // do nothing
               }
           }

           MetaMethod addListenerMethod = (MetaMethod) listeners.get(property);
           if (addListenerMethod != null) {
               /* @todo one day we could try return the previously registered Closure listener for easy removal */
               return null;
           }

           if (lastException == null)
               throw new MissingPropertyException(property, theClass);
           else
               throw new MissingPropertyException(property, theClass, lastException);
       }
   }

   /**
    * Get all the properties defined for this type
    * @return a list of MetaProperty objects
    */
   public List getProperties() {
       checkInitalised();
       // simply return the values of the metaproperty map as a List
       List ret = new ArrayList(propertyMap.size());
       for (Iterator iter = propertyMap.values().iterator(); iter.hasNext();) {
           MetaProperty element = (MetaProperty) iter.next();
           if (element instanceof MetaFieldProperty) continue;
           ret.add(element);
       }
       return ret;
   }

   /**
    * This will build up the property map (Map of MetaProperty objects, keyed on
    * property name).
    */
   private void setupProperties(PropertyDescriptor[] propertyDescriptors) {
       MetaProperty mp;
       Method method;
       MetaMethod getter = null;
       MetaMethod setter = null;
       Class klass;

       // first get the public fields and create MetaFieldProperty objects
       klass = theClass;
       while(klass != null) {
           final Class clazz = klass;
           Field[] fields = (Field[]) AccessController.doPrivileged(new  PrivilegedAction() {
               public Object run() {
                   return clazz.getDeclaredFields();
               }
           });
           for(int i = 0; i < fields.length; i++) {
               // todo: GROOVY-996
               // we're only interested in publics and protected
               if ((fields[i].getModifiers() & (java.lang.reflect.Modifier.PUBLIC | java.lang.reflect.Modifier.PROTECTED)) == 0)
                    continue;

               // see if we already got this
               if(propertyMap.get(fields[i].getName()) != null)
                   continue;

               //System.out.println("adding field " + fields[i].getName() +
               //  " for class " + klass.getName());
               // stick it in there!
               propertyMap.put(fields[i].getName(), new MetaFieldProperty(fields[i]));
           }

           // now get the super class
           klass = klass.getSuperclass();
       }

  // if this an Array, then add the special read-only "length" property
       if (theClass.isArray()) {
           propertyMap.put("length", arrayLengthProperty);
       }

       // now iterate over the map of property descriptors and generate
       // MetaBeanProperty objects
       for(int i=0; i<propertyDescriptors.length; i++) {
           PropertyDescriptor pd = propertyDescriptors[i];

           // skip if the property type is unknown (this seems to be the case if the
           // property descriptor is based on a setX() method that has two parameters,
           // which is not a valid property)
           if(pd.getPropertyType() == null)
               continue;

           // get the getter method
           method = pd.getReadMethod();
           if(method != null)
               getter = findMethod(method);
           else
               getter = null;

           // get the setter method
           method = pd.getWriteMethod();
           if(method != null)
               setter = findMethod(method);
           else
               setter = null;

           // now create the MetaProperty object
           //System.out.println("creating a bean property for class " +
           //  theClass.getName() + ": " + pd.getName());

           mp = new MetaBeanProperty(pd.getName(), pd.getPropertyType(), getter, setter);

           // put it in the list
           // this will overwrite a possible field property
           propertyMap.put(pd.getName(), mp);
       }

       // now look for any stray getters that may be used to define a property
       klass = theClass;
       while(klass != null) {
           final Class clazz = klass;
           Method[] methods = (Method[]) AccessController.doPrivileged(new  PrivilegedAction() {
               public Object run() {
                   return clazz.getDeclaredMethods();
               }
           });
           for (int i = 0; i < methods.length; i++) {
               // filter out the privates
               if(Modifier.isPublic(methods[i].getModifiers()) == false)
                   continue;

               method = methods[i];

               String methodName = method.getName();

               // is this a getter?
               if(methodName.startsWith("get") &&
                   methodName.length() > 3 &&
                   method.getParameterTypes().length == 0) {

                   // get the name of the property
                   String propName = methodName.substring(3,4).toLowerCase() + methodName.substring(4);

                   // is this property already accounted for?
                   mp = (MetaProperty) propertyMap.get(propName);
                   if(mp != null) {
                       // we may have already found the setter for this
                       if(mp instanceof MetaBeanProperty && ((MetaBeanProperty) mp).getGetter() == null) {
                           // update the getter method to this one
                           ((MetaBeanProperty) mp).setGetter(findMethod(method));
                       }
                   }
                   else {
                       // we need to create a new property object
                       // type of the property is what the get method returns
                       MetaBeanProperty mbp = new MetaBeanProperty(propName,
                           method.getReturnType(),
                           findMethod(method), null);

                       // add it to the map
                       propertyMap.put(propName, mbp);
                   }
               }
               else if(methodName.startsWith("set") &&
                   methodName.length() > 3 &&
                   method.getParameterTypes().length == 1) {

                   // get the name of the property
                   String propName = methodName.substring(3,4).toLowerCase() + methodName.substring(4);

                   // did we already find the getter of this?
                   mp = (MetaProperty) propertyMap.get(propName);
                   if(mp != null) {
                       if(mp instanceof MetaBeanProperty && ((MetaBeanProperty) mp).getSetter() == null) {
                           // update the setter method to this one
                           ((MetaBeanProperty) mp).setSetter(findMethod(method));
                       }
                   }
                   else {
                       // this is a new property to add
                       MetaBeanProperty mbp = new MetaBeanProperty(propName,
                                                                   method.getParameterTypes()[0],
                                                                   null,
                                                                   findMethod(method));

                       // add it to the map
                       propertyMap.put(propName, mbp);
                   }
               }
           }

           // now get the super class
           klass = klass.getSuperclass();
       }
   }

   /**
    * Sets the property value on an object
    */
   public void setProperty(Object object, String property, Object newValue) { 
       checkInitalised();
       
       //
       // Unwrap wrapped values fo now - the new MOP will handle them properly
       //
       if (newValue instanceof Wrapper) newValue = ((Wrapper)newValue).unwrap();
       
       MetaProperty mp = (MetaProperty) propertyMap.get(property);
       if(mp != null) {
           try {
               mp.setProperty(object, newValue);
               return;
           }
           catch(ReadOnlyPropertyException e) {
               // just rethrow it; there's nothing left to do here
               throw e;
           }
           catch (TypeMismatchException e) {
               // tried to access to mismatched object.
               throw e;
           }
           catch (Exception e) {
               // if the value is a List see if we can construct the value
               // from a constructor
               if (newValue == null)
                   return;
               if (newValue instanceof List) {
                   List list = (List) newValue;
                   int params = list.size();
                   Constructor[] constructors = mp.getType().getConstructors();
                   for (int i = 0; i < constructors.length; i++) {
                       Constructor constructor = constructors[i];
                       if (constructor.getParameterTypes().length == params) {
                           Object value = MetaClassHelper.doConstructorInvoke(constructor, list.toArray());
                           mp.setProperty(object, value);
                           return;
                       }
                   }

                   // if value is an array
                   Class parameterType = mp.getType();
                   if (parameterType.isArray()) {
                       Object objArray = MetaClassHelper.asPrimitiveArray(list, parameterType);
                       mp.setProperty(object, objArray);
                       return;
                   }
               }

               // if value is an multidimensional array
               // jes currently this logic only supports metabeansproperties and
               // not metafieldproperties. It shouldn't be too hard to support
               // the latter...
               if (newValue.getClass().isArray() && mp instanceof MetaBeanProperty) {
                   MetaBeanProperty mbp = (MetaBeanProperty) mp;
                   List list = Arrays.asList((Object[])newValue);
                   MetaMethod setter = mbp.getSetter();

                   Class parameterType = setter.getParameterTypes()[0];
                   Class arrayType = parameterType.getComponentType();
                   Object objArray = Array.newInstance(arrayType, list.size());

                   for (int i = 0; i < list.size(); i++) {
                       List list2 =Arrays.asList((Object[]) list.get(i));
                       Object objArray2 = MetaClassHelper.asPrimitiveArray(list2, arrayType);
                       Array.set(objArray, i, objArray2);
                   }

                   MetaClassHelper.doMethodInvoke(object, setter, new Object[]{
                       objArray
                   });
                   return;
               }

               throw new MissingPropertyException(property, theClass, e);
           }
       }

       RuntimeException runtimeException = null;
       MetaMethod addListenerMethod = (MetaMethod) listeners.get(property);
       
       try {           
           if (addListenerMethod != null && newValue instanceof Closure) {
               // lets create a dynamic proxy
               Object proxy =
                   MetaClassHelper.createListenerProxy(addListenerMethod.getParameterTypes()[0], property, (Closure) newValue);
               MetaClassHelper.doMethodInvoke(object, addListenerMethod, new Object[] { proxy });
               return;
           }

           if (genericSetMethod == null) {
               // Make sure there isn't a generic method in the "use" cases
               //TODO: implement!
               List possibleGenericMethods = getMethods(theClass,"set",false);
               if (possibleGenericMethods != null) {
                   for (Iterator i = possibleGenericMethods.iterator(); i.hasNext(); ) {
                       MetaMethod mmethod = (MetaMethod) i.next();
                       Class[] paramTypes = mmethod.getParameterTypes();
                       if (paramTypes.length == 2 && paramTypes[0] == String.class) {
                           Object[] arguments = {property, newValue};
                           Object answer = MetaClassHelper.doMethodInvoke(object, mmethod, arguments);
                           return;
                       }
                   }
               }
           }
           else {
               Object[] arguments = { property, newValue };
               MetaClassHelper.doMethodInvoke(object, genericSetMethod, arguments);
               return;
           }

           /** todo or are we an extensible class? */

           // lets try invoke the set method
           // this is kind of ugly: if it is a protected field, we fall
           // all the way down to this klunky code. Need a better
           // way to handle this situation...

           String method = "set" + MetaClassHelper.capitalize(property);
           try {
               invokeMethod(object, method, new Object[] { newValue });
           }
           catch (MissingMethodException e1) {
               setAttribute(object,property,newValue);
           }

       }
       catch (GroovyRuntimeException e) {
           runtimeException = e;
       }
       
       if (addListenerMethod==AMBIGOUS_LISTENER_METHOD){
           throw new GroovyRuntimeException("There are multiple listeners for the property "+property+". Please do not use the bean short form to access this listener.");
       } else if (runtimeException!=null) {
           throw new MissingPropertyException(property, theClass, runtimeException);
       }
       

   }


   /**
    * Looks up the given attribute (field) on the given object
    */
   public Object getAttribute(final Object object, final String attribute) {
       checkInitalised();
       
       PrivilegedActionException firstException = null;

       final Class clazz;
       if (object instanceof Class) {
           clazz=(Class) object;
       } else {
           clazz=theClass;
       }

       try {
           return AccessController.doPrivileged(new PrivilegedExceptionAction() {
               public Object run() throws NoSuchFieldException, IllegalAccessException {
                   final Field field = clazz.getDeclaredField(attribute);

                   field.setAccessible(true);
                   return field.get(object);
               }
           });
       } catch (final PrivilegedActionException pae) {
           firstException = pae;
       }

       try {
           return AccessController.doPrivileged(new PrivilegedExceptionAction() {
               public Object run() throws NoSuchFieldException, IllegalAccessException {
                   final Field field = clazz.getField(attribute);

                   field.setAccessible(true);
                   return field.get(object);
               }
           });
       } catch (final PrivilegedActionException pae) {
           // prefere the first exception.
       }


       if (firstException.getException() instanceof NoSuchFieldException) {
           throw new MissingFieldException(attribute, theClass);
       } else {
           throw new RuntimeException(firstException.getException());
       }
   }

   /**
    * Sets the given attribute (field) on the given object
    */
   public void setAttribute(final Object object, final String attribute, final Object newValue) {
       PrivilegedActionException firstException = null;

       final Class clazz;
       if (object instanceof Class) {
           clazz=(Class) object;
       } else {
           clazz=theClass;
       }

       try {
           AccessController.doPrivileged(new PrivilegedExceptionAction() {
               public Object run() throws NoSuchFieldException, IllegalAccessException {
                   final Field field = clazz.getDeclaredField(attribute);

                   field.setAccessible(true);
                   field.set(object,newValue);
                   return null;
               }
           });
           return;
       } catch (final PrivilegedActionException pae) {
           firstException = pae;
       }

       try {
           AccessController.doPrivileged(new PrivilegedExceptionAction() {
               public Object run() throws NoSuchFieldException, IllegalAccessException {
                   final Field field = clazz.getField(attribute);

                   field.setAccessible(true);
                   field.set(object, newValue);
                   return null;
               }
           });
           return;
       } catch (final PrivilegedActionException pae) {
           // prefere the first exception.
       }

       if (firstException.getException() instanceof NoSuchFieldException) {
           throw new MissingFieldException(attribute, theClass);
       } else {
           throw new RuntimeException(firstException.getException());
       }
   }

   public ClassNode getClassNode() {
       if (classNode == null && GroovyObject.class.isAssignableFrom(theClass)) {
           // lets try load it from the classpath
           String className = theClass.getName();
           String groovyFile = className;
           int idx = groovyFile.indexOf('$');
           if (idx > 0) {
               groovyFile = groovyFile.substring(0, idx);
           }
           groovyFile = groovyFile.replace('.', '/') + ".groovy";

           //System.out.println("Attempting to load: " + groovyFile);
           URL url = theClass.getClassLoader().getResource(groovyFile);
           if (url == null) {
               url = Thread.currentThread().getContextClassLoader().getResource(groovyFile);
           }
           if (url != null) {
               try {

                   /**
                    * todo there is no CompileUnit in scope so class name
                    * checking won't work but that mostly affects the bytecode
                    * generation rather than viewing the AST
                    */
                   CompilationUnit.ClassgenCallback search = new CompilationUnit.ClassgenCallback() {
                       public void call( ClassVisitor writer, ClassNode node ) {
                           if( node.getName().equals(theClass.getName()) ) {
                               MetaClassImpl.this.classNode = node;
                           }
                       }
                   };

                   final ClassLoader parent = theClass.getClassLoader();
                   GroovyClassLoader gcl = (GroovyClassLoader) AccessController.doPrivileged(new PrivilegedAction() {
                       public Object run() {
                           return new GroovyClassLoader(parent);
                       }
                   });
                   CompilationUnit unit = new CompilationUnit( );
                   unit.setClassgenCallback( search );
                   unit.addSource( url );
                   unit.compile( Phases.CLASS_GENERATION );
               }
               catch (Exception e) {
                   throw new GroovyRuntimeException("Exception thrown parsing: " + groovyFile + ". Reason: " + e, e);
               }
           }

       }
       return classNode;
   }

   public String toString() {
       return super.toString() + "[" + theClass + "]";
   }

   // Implementation methods
   //-------------------------------------------------------------------------
   
   /**
    * Adds all the methods declared in the given class to the metaclass
    * ignoring any matching methods already defined by a derived class
    *
    * @param theClass
    */
   private void addMethods(final Class theClass) {
       Map methodIndex = (Map) classMethodIndex.get(theClass);
       if (methodIndex==null) {
           methodIndex = new HashMap();
           classMethodIndex.put(theClass,methodIndex);
       }
       // add methods directly declared in the class
       Method[] methodArray = (Method[]) AccessController.doPrivileged(new  PrivilegedAction() {
               public Object run() {
                   return theClass.getDeclaredMethods();
               }
           });
       for (int i = 0; i < methodArray.length; i++) {
           Method reflectionMethod = methodArray[i];
           if ( reflectionMethod.getName().indexOf('+') >= 0 ) {
               // Skip Synthetic methods inserted by JDK 1.5 compilers and later
               continue;
           } else if (Modifier.isAbstract(reflectionMethod.getModifiers())) {
               continue;
           }
           MetaMethod method = createMetaMethod(reflectionMethod);
           addMethod(method);
       }
       // add methods declared by DGM
       List methods = registry.getInstanceMethods();
       for (Iterator iter = methods.iterator(); iter.hasNext();) {
           Method element = (Method) iter.next();
           if (element.getParameterTypes()[0]!=theClass) continue;
           addNewInstanceMethod(element);
       }
       // add static methods declared by DGM
       methods = registry.getStaticMethods();
       for (Iterator iter = methods.iterator(); iter.hasNext();) {
           Method element = (Method) iter.next();
           if (element.getParameterTypes()[0]!=theClass) continue;
           addNewStaticMethod(element);
       }
   }
   
   private void addtoClassMethodIndex(MetaMethod method, Map classMethodIndex) {
       Map methodIndex = (Map) classMethodIndex.get(method.getDeclaringClass());
       if (methodIndex==null) {
           methodIndex = new HashMap();
           classMethodIndex.put(method.getDeclaringClass(),methodIndex);
       }
       String name = method.getName();
       List list = (List) methodIndex.get(name);
       if (list == null) {
           list = new ArrayList();
           methodIndex.put(name, list);
           list.add(method);
       } else {
           addMethodToList(list,method);
       }
   }

   private void addMethod(MetaMethod method) {
       if (isGenericGetMethod(method) && genericGetMethod == null) {
           genericGetMethod = method;
       }
       else if (MetaClassHelper.isGenericSetMethod(method) && genericSetMethod == null) {
           genericSetMethod = method;
       }
       if (method.isStatic()) {
           addtoClassMethodIndex(method,classStaticMethodIndex);
       }
       addtoClassMethodIndex(method,classMethodIndex);
   }
   
   private void addMethodToList(List list, MetaMethod method) {
       MetaMethod match = removeMatchingMethod(list,method);
       if (match==null) {
           list.add(method);
       } else if (match.isPrivate()){
           // do not overwrite private methods
           // Note: private methods from parent classes are not shown here,
           // but when doing the multimethod connection step, we overwrite
           // methods of the parent class with methods of a subclass and
           // in that case we want to keep the private methods
           list.add(match);
       } else {
           Class methodC = method.getDeclaringClass();
           Class matchC = match.getDeclaringClass();
           if (methodC == matchC){
               if (method instanceof NewInstanceMetaMethod) {
                   // let DGM replace existing methods
                   list.add(method);
               } else {
                   list.add(match);
               }               
           } else if (MetaClassHelper.isAssignableFrom(methodC,matchC)){
               list.add(match);
           } else {
              list.add(method);
           }
       }
   }
   
   /**
    * remove a method of the same matching prototype was found in the list
    */
   private MetaMethod removeMatchingMethod(List list, MetaMethod method) {
       for (Iterator iter = list.iterator(); iter.hasNext();) {
           MetaMethod aMethod = (MetaMethod) iter.next();
           Class[] params1 = aMethod.getParameterTypes();
           Class[] params2 = method.getParameterTypes();
           if (params1.length == params2.length) {
               boolean matches = true;
               for (int i = 0; i < params1.length; i++) {
                   if (params1[i] != params2[i]) {
                       matches = false;
                       break;
                   }
               }
               if (matches) {
                   iter.remove();
                   return (MetaMethod) aMethod;
               }
           }
       }
       return null;
   }

   /**
    * @return the value of the static property of the given class
    */
   private Object getStaticProperty(Class aClass, String property) {
       //System.out.println("Invoking property: " + property + " on class: "
       // + aClass);

       // lets try invoke a static getter method
       MetaMethod method = findStaticGetter(aClass, "get" + MetaClassHelper.capitalize(property));
       if (method != null) {
           return MetaClassHelper.doMethodInvoke(aClass, method, MetaClassHelper.EMPTY_ARRAY);
       }

       //no static getter found, try attribute  
       try {
           return getAttribute(aClass,property);
       } catch (MissingFieldException mfe) {
           throw new MissingPropertyException(property, aClass, mfe);
       }
   }

   /**
    * @return the matching method which should be found
    */
   private MetaMethod findMethod(Method aMethod) {
       //TODO: implement!
       List methods = getMethods(theClass,aMethod.getName(),false);
       for (Iterator iter = methods.iterator(); iter.hasNext();) {
           MetaMethod method = (MetaMethod) iter.next();
           if (method.isMethod(aMethod)) {
               return method;
           }
       }
       //log.warning("Creating reflection based dispatcher for: " + aMethod);
       return new ReflectionMetaMethod(aMethod);
   }

   /**
    * @return the getter method for the given object
    */
   private MetaMethod findGetter(Object object, String name) {
       //TODO: implment!
       List methods = getMethods(theClass,name,false);
       for (Iterator iter = methods.iterator(); iter.hasNext();) {
           MetaMethod method = (MetaMethod) iter.next();
           if (method.getParameterTypes().length == 0) {
               return method;
           }
       }
       return null;
   }

   /**
    * @return the Method of the given name with no parameters or null
    */
   private MetaMethod findStaticGetter(Class type, String name) {
       List methods = getStaticMethods(type, name);
       for (Iterator iter = methods.iterator(); iter.hasNext();) {
           MetaMethod method = (MetaMethod) iter.next();
           if (method.getParameterTypes().length == 0) {
               return method;
           }
       }

       /** todo dirty hack - don't understand why this code is necessary - all methods should be in the allMethods list! */
       try {
           Method method = type.getMethod(name, MetaClassHelper.EMPTY_TYPE_ARRAY);
           if ((method.getModifiers() & Modifier.STATIC) != 0) {
               return findMethod(method);
           }
           else {
               return null;
           }
       }
       catch (Exception e) {
           return null;
       }
   }
   
   private static Object doConstructorInvoke(final Class at, Constructor constructor, Object[] argumentArray, boolean setAccessible) {
       if (log.isLoggable(Level.FINER)) {
           MetaClassHelper.logMethodCall(constructor.getDeclaringClass(), constructor.getName(), argumentArray);
       }

       if (setAccessible) {
           // To fix JIRA 435
           // Every constructor should be opened to the accessible classes.
           final boolean accessible = MetaClassHelper.accessibleToConstructor(at, constructor);
           final Constructor ctor = constructor;
           AccessController.doPrivileged(new PrivilegedAction() {
               public Object run() {
                   ctor.setAccessible(accessible);
                   return null;
               }
           });
       }
       return MetaClassHelper.doConstructorInvoke(constructor,argumentArray);
   }

   /**
    * Chooses the correct method to use from a list of methods which match by
    * name.
    *
    * @param methods
    *            the possible methods to choose from
    * @param arguments
    *            the original argument to the method
    */
   private Object chooseMethod(String methodName, List methods, Class[] arguments, boolean coerce) {
       int methodCount = methods.size();
       if (methodCount <= 0) {
           return null;
       }
       else if (methodCount == 1) {
           Object method = methods.get(0);
           if (MetaClassHelper.isValidMethod(method, arguments, coerce)) {
               return method;
           }
           return null;
       }
       Object answer = null;
       if (arguments == null || arguments.length == 0) {
           answer = MetaClassHelper.chooseEmptyMethodParams(methods);
       }
       else if (arguments.length == 1 && arguments[0] == null) {
           answer = MetaClassHelper.chooseMostGeneralMethodWith1NullParam(methods);
       }
       else {
           List matchingMethods = new ArrayList();

           for (Iterator iter = methods.iterator(); iter.hasNext();) {
               Object method = iter.next();

               // making this false helps find matches
               if (MetaClassHelper.isValidMethod(method, arguments, coerce)) {
                   matchingMethods.add(method);
               }
           }
           if (matchingMethods.isEmpty()) {
               return null;
           }
           else if (matchingMethods.size() == 1) {
               return matchingMethods.get(0);
           }
           return chooseMostSpecificParams(methodName, matchingMethods, arguments);

       }
       if (answer != null) {
           return answer;
       }
       throw new GroovyRuntimeException(
           "Could not find which method to invoke from this list: "
               + methods
               + " for arguments: "
               + InvokerHelper.toString(arguments));
   }

   private Object chooseMostSpecificParams(String name, List matchingMethods, Class[] arguments) {

       Class[] wrappedArguments = MetaClassHelper.wrap(arguments);

       int matchesDistance = -1;
       LinkedList matches = new LinkedList();
       for (Iterator iter = matchingMethods.iterator(); iter.hasNext();) {
           Object method = iter.next();
           Class[] paramTypes = MetaClassHelper.getParameterTypes(method);
           if (!MetaClassHelper.parametersAreCompatible(arguments, paramTypes)) continue;
           int dist = MetaClassHelper.calculateParameterDistance(arguments, paramTypes);
           if (matches.size()==0) {
               matches.add(method);
               matchesDistance = dist;
           } else if (dist<matchesDistance) {
               matchesDistance=dist;
               matches.clear();
               matches.add(method);
           } else if (dist==matchesDistance) {
               matches.add(method);
           }

       }
       if (matches.size()==1) {
           return matches.getFirst();
       }
       if (matches.size()==0) {
           return null;
       }

       //more than one matching method found --> ambigous!
       String msg = "Ambiguous method overloading for method ";
       msg+= theClass.getName()+"#"+name;
       msg+= ".\nCannot resolve which method to invoke for ";
       msg+= InvokerHelper.toString(arguments);
       msg+= " due to overlapping prototypes between:";
       for (Iterator iter = matches.iterator(); iter.hasNext();) {
           Class[] types=MetaClassHelper.getParameterTypes(iter.next());
           msg+= "\n\t"+InvokerHelper.toString(types);
       }
       throw new GroovyRuntimeException(msg);
   }

   private boolean isGenericGetMethod(MetaMethod method) {
       if (method.getName().equals("get")) {
           Class[] parameterTypes = method.getParameterTypes();
           return parameterTypes.length == 1 && parameterTypes[0] == String.class;
       }
       return false;
   }

   /**
    * Call this method when any mutation method is called, such as adding a new
    * method to this MetaClass so that any caching or bytecode generation can be
    * regenerated.
    */
   private synchronized void onMethodChange() {
       reflector = null;
   }

   public synchronized void initialise() {
       if (!initialised) {
           fillMethodIndex();
           addProperties();
           initialised = true;
       }
       if (reflector == null) {
           generateReflector();
       }
   }

   private void addProperties()  {
       
       PropertyDescriptor[] descriptors = info.getPropertyDescriptors();

       // build up the metaproperties based on the public fields, property descriptors,
       // and the getters and setters
       setupProperties(descriptors);


       EventSetDescriptor[] eventDescriptors = info.getEventSetDescriptors();
       for (int i = 0; i < eventDescriptors.length; i++) {
           EventSetDescriptor descriptor = eventDescriptors[i];
           Method[] listenerMethods = descriptor.getListenerMethods();
           for (int j = 0; j < listenerMethods.length; j++) {
               Method listenerMethod = listenerMethods[j];
               MetaMethod metaMethod = createMetaMethod(descriptor.getAddListenerMethod());
               String name = listenerMethod.getName();
               if (listeners.containsKey(name)) {
                   listeners.put(name, AMBIGOUS_LISTENER_METHOD);
               } else{
                   listeners.put(name, metaMethod);
               }
           }
       }    
   }

private MetaMethod createMetaMethod(final Method method) {
       if (registry.useAccessible()) {
           AccessController.doPrivileged(new PrivilegedAction() {
               public Object run() {
                   method.setAccessible(true);
                   return null;
               }
           });
       }

       MetaMethod answer = new MetaMethod(method);
       if (isValidReflectorMethod(answer)) {
           allMethods.add(answer);
           answer.setMethodIndex(allMethods.size());
       }
       else {
           //log.warning("Creating reflection based dispatcher for: " + method);
           answer = new ReflectionMetaMethod(method);
       }

       if (useReflection) {
           //log.warning("Creating reflection based dispatcher for: " + method);
           return new ReflectionMetaMethod(method);
       }

       return answer;
   }

   private boolean isValidReflectorMethod(MetaMethod method) {
       // We cannot use a reflector if the method is private, protected, or package accessible only.
       if (!method.isPublic()) {
           return false;
       }
       // lets see if this method is implemented on an interface
       List interfaceMethods = getInterfaceMethods();
       for (Iterator iter = interfaceMethods.iterator(); iter.hasNext();) {
           MetaMethod aMethod = (MetaMethod) iter.next();
           if (method.isSame(aMethod)) {
               method.setInterfaceClass(aMethod.getCallClass());
               return true;
           }
       }
       // it's no interface method, so try to find the highest class
       // in hierarchy defining this method
       Class declaringClass = method.getCallClass();
       for (Class clazz=declaringClass; clazz!=null; clazz=clazz.getSuperclass()) {
           try {
               final Class klazz = clazz;
               final String mName = method.getName();
               final Class[] parms = method.getParameterTypes();
               try {
                   Method m = (Method) AccessController.doPrivileged(new PrivilegedExceptionAction() {
                       public Object run() throws NoSuchMethodException {
                           return klazz.getDeclaredMethod(mName, parms);
                       }
                   });
                   if (!Modifier.isPublic(clazz.getModifiers())) continue;
                   if (!Modifier.isPublic(m.getModifiers())) continue;
                   declaringClass = clazz;
               } catch (PrivilegedActionException pae) {
                   if (pae.getException() instanceof NoSuchMethodException) {
                       throw (NoSuchMethodException) pae.getException();
                   } else {
                       throw new RuntimeException(pae.getException());
                   }
               }
           } catch (SecurityException e) {
               continue;
           } catch (NoSuchMethodException e) {
               continue;
           }
       }
       if (!Modifier.isPublic(declaringClass.getModifiers())) return false;
       method.setCallClass(declaringClass);

       return true;
   }

   private void generateReflector() {
       reflector = registry.loadReflector(theClass, allMethods);
       if (reflector == null) {
           throw new RuntimeException("Should have a reflector for "+theClass.getName());
       }
       // lets set the reflector on all the methods
       for (Iterator iter = allMethods.iterator(); iter.hasNext();) {
           MetaMethod metaMethod = (MetaMethod) iter.next();
           metaMethod.setReflector(reflector);
       }
   }

   public List getMethods() {
       return allMethods;
   }

   public List getMetaMethods() {
       return new ArrayList(newGroovyMethodsList);
   }

   private synchronized List getInterfaceMethods() {
       if (interfaceMethods == null) {
           interfaceMethods = new ArrayList();
           Class type = theClass;
           while (type != null) {
               Class[] interfaces = type.getInterfaces();
               for (int i = 0; i < interfaces.length; i++) {
                   Class iface = interfaces[i];
                   Method[] methods = iface.getMethods();
                   addInterfaceMethods(interfaceMethods, methods);
               }
               type = type.getSuperclass();
           }
       }
       return interfaceMethods;
   }

   private void addInterfaceMethods(List list, Method[] methods) {
       for (int i = 0; i < methods.length; i++) {
           list.add(createMetaMethod(methods[i]));
       }
   }
}
