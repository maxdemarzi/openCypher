/*
 * Copyright (c) 2015-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
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
package org.opencypher.tools;

import java.io.Serializable;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static java.util.Arrays.asList;
import static java.util.Objects.requireNonNull;

import static org.opencypher.tools.Functions.map;
import static org.opencypher.tools.Reflection.defaultInvoker;

/**
 * Contains utilities for implementing <i>options interfaces</i>.
 * <p>
 * An <i>options interface</i> is an {@code interface} that only declare methods that take no arguments, and return a
 * <i>value object</i>, typically with default value implementations. Example:
 * <pre><code>
 * class UserOfOptions {
 *     public interface MyOptions {
 *         default Date targetDate() { return new Date(); }
 *         default Font prettyFont() { return new Font( fontName(), Font.PLAIN, fontSize() ); }
 *         default String fontName() { return "Verdana"; }
 *         default int fontSize() { return 11; }
 *     }
 *     private final Date targetDate;
 *     private final Font prettyFont;
 *     public UserOfOptions( MyOptions options ) {
 *         this.targetDate = options.targetDate();
 *         this.prettyFont = options.prettyFont();
 *     }
 * }
 * </code></pre>
 * This class contains a DSL that {@linkplain Reflection#lambdaParameterName(Serializable) uses reflection} to make it
 * convenient to create a custom instance of an <i>options interface</i>. Example:
 * <pre><code>
 * UserOfOptions[] custom = {
 *     new UserOfOptions( options( UserOfOptions.MyOptions.class,
 *         fontName -&gt; "Times New Roman",
 *         fontSize -&gt; 22 ) ),
 *     new UserOfOptions( options( UserOfOptions.MyOptions.class,
 *         targetDate -&gt; new Date( 1963, 11, 22 ),
 *         prettyFont -&gt; new Font( "Arial", Font.BOLD, prettyFont.fontSize() ) ),
 * };
 * </code></pre>
 *
 * @param <T> The value type of the <i>options interface</i> that this option customizes.
 */
@FunctionalInterface
public interface Option<T> extends Serializable
{
    Object value( T options );

    /**
     * Create a dynamic implementation of the supplied <i>options interface</i> using the specified options to define
     * values. The specified options must be lambdas where the parameter name of the lambda is the name of the option
     * of the options interface that lambda overrides.
     * When the lambda is invoked, the instance of the options interface is given as the sole parameter, this allows
     * custom options that depend on other options of the options interface.
     * For options that are not overridden the lookup function provided is used to lookup the value, and if the lookup
     * function returns {@code null} the default value is used.
     *
     * @param optionsType the <i>options interface</i> to implement.
     * @param lookup      the function to use to look up values not explicitly overridden.
     * @param options     lambdas that define the overridden options.
     * @param <T>         the type of the <i>options interface</i>.
     * @return an instance of the <i>options interface</i>.
     */
    @SafeVarargs
    static <T> T dynamicOptions( Class<T> optionsType, Function<Method, Object> lookup, Option<? super T>... options )
    {
        return OptionHandler.create( optionsType, requireNonNull( lookup, "lookup" ), options );
    }

    /**
     * Create a dynamic implementation of the supplied <i>options interface</i> using the specified options to define
     * values. The specified options must be lambdas where the parameter name of the lambda is the name of the option
     * of the options interface that lambda overrides.
     * When the lambda is invoked, the instance of the options interface is given as the sole parameter, this allows
     * custom options that depend on other options of the options interface.
     * For options that are not given, the default value is used.
     *
     * @param optionsType the <i>options interface</i> to implement.
     * @param options     lambdas that define the overridden options.
     * @param <T>         the type of the <i>options interface</i>.
     * @return an instance of the <i>options interface</i>.
     */
    @SafeVarargs
    static <T> T options( Class<T> optionsType, Option<? super T>... options )
    {
        return OptionHandler.create( optionsType, null, options );
    }

    /**
     * Implementation detail: the {@link InvocationHandler} used for implementing an <i>options interface</i>.
     *
     * @param <T> the implemented <i>options interface</i>.
     */
    class OptionHandler<T> implements InvocationHandler
    {
        @SafeVarargs
        private static <T> T create( Class<T> iFace, Function<Method, Object> lookup, Option<? super T>... options )
        {
            if ( !iFace.isInterface() )
            {
                throw new IllegalArgumentException( "options must be an interface: " + iFace );
            }
            Map<String, Option<? super T>> optionMap = new HashMap<>(
                    map( asList( options ), Reflection::lambdaParameterName ) );
            Map<String, Method> methods = map( asList( iFace.getMethods() ), ( method ) -> {
                if ( method.getDeclaringClass() == Object.class )
                {
                    return null;
                }
                if ( method.getParameterCount() != 0 )
                {
                    throw new IllegalArgumentException(
                            "Options interface may not have methods with parameters: " + method );
                }
                if ( !(method.isDefault() || optionMap.containsKey( method.getName() )) && lookup == null )
                {
                    throw new IllegalArgumentException( "Missing required option: " + method.getName() );
                }
                return method.getName();
            } );
            optionMap.keySet().forEach( name -> {
                Method method = methods.get( name );
                if ( method == null )
                {
                    throw new IllegalArgumentException( "No such option: " + name );
                }
            } );
            return iFace.cast( Proxy.newProxyInstance(
                    iFace.getClassLoader(), new Class[]{iFace},
                    new OptionHandler<T>( lookup == null ? (name -> null) : lookup, optionMap ) ) );
        }

        private final Function<Method, Object> dynamic;
        private final Map<String, Option<? super T>> options;

        private OptionHandler( Function<Method, Object> dynamic, Map<String, Option<? super T>> options )
        {
            this.dynamic = dynamic;
            this.options = options;
        }

        @Override
        public Object invoke( Object proxy, Method method, Object[] args ) throws Throwable
        {
            if ( method.getDeclaringClass() == Object.class )
            {
                switch ( method.getName() )
                {
                case "toString":
                    return proxy.getClass().getName();
                case "hashCode":
                    return System.identityHashCode( proxy );
                case "equals":
                    return proxy == args[0];
                }
            }
            String name = method.getName();
            Option<? super T> option = options.get( name );
            if ( option == null )
            {
                Object value = dynamic.apply( method );
                if ( value != null )
                {
                    options.put( name, option = option( value ) );
                }
                else
                {
                    options.put( name, option = option( defaultInvoker( method ) ) );
                }
            }
            return invoke( option, proxy );
        }

        @SuppressWarnings("unchecked")
        private static Object invoke( Option option, Object options )
        {
            return option.value( options );
        }

        private static <T> Option<T> option( Object value )
        {
            return options -> value;
        }

        private static <T> Option<T> option( MethodHandle invoker )
        {
            return ( options ) -> Reflection.invoke( invoker, options );
        }
    }
}
