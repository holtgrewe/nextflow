/*
 * Copyright (c) 2013-2014, Centre for Genomic Regulation (CRG).
 * Copyright (c) 2013-2014, Paolo Di Tommaso and the respective authors.
 *
 *   This file is part of 'Nextflow'.
 *
 *   Nextflow is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   Nextflow is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with Nextflow.  If not, see <http://www.gnu.org/licenses/>.
 */

package nextflow.processor

import groovyx.gpars.dataflow.DataflowVariable
import nextflow.script.BaseScript
import nextflow.script.FileInParam
import nextflow.script.InputsList
import nextflow.script.OutputsList
import nextflow.script.StdInParam
import nextflow.script.StdOutParam
import nextflow.script.TokenVar
import nextflow.script.ValueInParam
import nextflow.script.ValueSharedParam
import nextflow.util.Duration
import nextflow.util.MemoryUnit
import spock.lang.Specification

import static nextflow.util.CacheHelper.HashMode


/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class TaskConfigTest extends Specification {


    def 'test defaults' () {

        setup:
        def script = Mock(BaseScript)
        def config = new TaskConfig(script)

        expect:
        config.shell ==  ['/bin/bash','-ue']
        config.cacheable
        config.validExitStatus == [0]
        config.errorStrategy == ErrorStrategy.TERMINATE
        config.inputs instanceof InputsList
        config.outputs instanceof OutputsList
    }

    def 'test setting properties' () {

        setup:
        def script = Mock(BaseScript)
        def config = new TaskConfig(script)

        // setting property using method without brackets
        when:
        config.hola 'val 1'
        then:
        config.hola == 'val 1'

        // setting list values
        when:
        config.hola 1,2,3
        then:
        config.hola == [1,2,3]

        // setting named parameters attribute
        when:
        config.hola field1:'val1', field2: 'val2'
        then:
        config.hola == [field1:'val1', field2: 'val2']

        // maxDuration property
        when:
        config.maxDuration '1h'
        then:
        config.time == new Duration('1h')
        config.time as Duration == new Duration('1h')

        // maxMemory property
        when:
        config.maxMemory '2GB'
        then:
        config.memory == new MemoryUnit('2GB')

        // generic value assigned like a 'plain' property
        when:
        config.hola = 99
        then:
        config.hola == 99

    }

    def testParseProperties() {

        when:
        def config = new TaskConfig( maxDuration:'1h' )
        then:
        config.maxDuration as Duration == Duration.of('1h')
    }


    def 'test NO missingPropertyException' () {

        when:
        def script = Mock(BaseScript)
        def config = new TaskConfig(script)
        def x = config.hola

        then:
        x == null
        noExceptionThrown()

    }

    def 'test MissingPropertyException' () {
        when:
        def script = Mock(BaseScript)
        def config = new TaskConfig(script).throwExceptionOnMissingProperty(true)
        def x = config.hola

        then:
        thrown(MissingPropertyException)
    }


    def 'test check property existence' () {

        setup:
        def script = Mock(BaseScript)
        def config = new TaskConfig(script)

        expect:
        config.containsKey('echo')
        config.containsKey('shell')
        config.containsKey('validExitStatus')
        config.containsKey('inputs')
        config.containsKey('outputs')
        config.containsKey('undef')
        !config.containsKey('xyz')
        !config.containsKey('maxForks')
        config.maxForks == null

    }

    def 'test undef' () {

        setup:
        def script = Mock(BaseScript)
        def config = new TaskConfig(script)

        expect:
        config.undef == false

        when:
        config.undef(true)
        then:
        config.undef == true

    }


    def 'test input' () {

        setup:
        def script = Mock(BaseScript)
        def config = new TaskConfig(script)

        when:
        config._in_file([infile:'filename.fa'])
        config._in_val('x') .from(1)
        config._in_stdin()

        then:
        config.getInputs().size() == 3

        config.inputs.get(0) instanceof FileInParam
        config.inputs.get(0).name == 'infile'
        (config.inputs.get(0) as FileInParam).filePattern == 'filename.fa'

        config.inputs.get(1) instanceof ValueInParam
        config.inputs.get(1).name == 'x'

        config.inputs.get(2).name == '-'
        config.inputs.get(2) instanceof StdInParam

        config.inputs.names == [ 'infile', 'x', '-' ]
        config.inputs.ofType( FileInParam ) == [ config.getInputs().get(0) ]

    }

    def 'test outputs' () {

        setup:
        def script = Mock(BaseScript)
        def config = new TaskConfig(script)

        when:
        config._out_stdout()
        config._out_file('file1.fa').into('ch1')
        config._out_file('file2.fa').into('ch2')
        config._out_file('file3.fa').into('ch3')

        then:
        config.outputs.size() == 4
        config.outputs.names == ['-', 'file1.fa', 'file2.fa', 'file3.fa']
        config.outputs.ofType(StdOutParam).size() == 1

        config.outputs[0] instanceof StdOutParam
        config.outputs[1].name == 'file1.fa'
        config.outputs[2].name == 'file2.fa'
        config.outputs[3].name == 'file3.fa'


    }

    /*
     *  shared: val x (seed x)
     *  shared: val x seed y
     *  shared: val x seed y using z
     */
    def testSharedValue() {

        setup:
        def binding = new Binding()
        def script = Mock(BaseScript)
        script.getBinding() >> { binding }

        when:
        def config = new TaskConfig(script)
        def val = config._share_val( new TokenVar('xxx'))
        then:
        val instanceof ValueSharedParam
        val.name == 'xxx'
        val.inChannel.val == null
        val.outChannel == null

        when:
        binding.setVariable('yyy', 'Hola')
        config = new TaskConfig(script)
        val = config._share_val(new TokenVar('yyy'))
        then:
        val instanceof ValueSharedParam
        val.name == 'yyy'
        val.inChannel.val == 'Hola'
        val.outChannel == null

        // specifying a value with the 'using' method
        // that value is bound to the input channel
        when:
        config = new TaskConfig(script)
        val = config._share_val('yyy') .from('Beta')
        then:
        val instanceof ValueSharedParam
        val.name == 'yyy'
        val.inChannel.val == 'Beta'
        val.outChannel == null

        // specifying a 'closure' with the 'using' method
        // that value is bound to the input channel
        when:
        config = new TaskConfig(script)
        val = config._share_val('yyy') .from({ 99 })
        then:
        val instanceof ValueSharedParam
        val.name == 'yyy'
        val.inChannel.val == 99
        val.outChannel == null


        // specifying a 'channel' it is reused
        // that value is bound to the input channel
        when:
        def channel = new DataflowVariable()
        channel << 123

        config = new TaskConfig(script)
        val = config._share_val('zzz') .from(channel)
        then:
        val instanceof ValueSharedParam
        val.name == 'zzz'
        val.inChannel.getVal() == 123
        val.outChannel == null

        // when a channel name is specified with the method 'into'
        // a DataflowVariable is created in the script context
        when:
        config = new TaskConfig(script)
        val = config._share_val(new TokenVar('x1')) .into( new TokenVar('x2') )
        then:
        val instanceof ValueSharedParam
        val.name == 'x1'
        val.inChannel.getVal() == null
        val.outChannel instanceof DataflowVariable
        binding.getVariable('x2') == val.outChannel

    }

    def testIsCacheable() {

        when:
        def config = new TaskConfig(map)
        then:
        config.cacheable == result
        config.isCacheable() == result
        config.getHashMode() == mode

        where:
        result | mode               | map
        true   | HashMode.STANDARD  | [:]
        true   | HashMode.STANDARD  | [cache:true]
        true   | HashMode.STANDARD  | [cache:'yes']
        true   | HashMode.DEEP      | [cache:'deep']
        false  | HashMode.STANDARD  | [cache:false]
        false  | HashMode.STANDARD  | [cache:'false']
        false  | HashMode.STANDARD  | [cache:'off']
        false  | HashMode.STANDARD  | [cache:'no']

    }

    def testErrorStrategy() {

        when:
        def config = new TaskConfig(map)
        then:
        config.errorStrategy == strategy
        config.getErrorStrategy() == strategy
        where:
        strategy                    | map
        null                        | [:]
        ErrorStrategy.TERMINATE     | [errorStrategy: 'terminate']
        ErrorStrategy.TERMINATE     | [errorStrategy: 'TERMINATE']
        ErrorStrategy.IGNORE        | [errorStrategy: 'ignore']
        ErrorStrategy.IGNORE        | [errorStrategy: 'Ignore']
        ErrorStrategy.RETRY         | [errorStrategy: 'retry']
        ErrorStrategy.RETRY         | [errorStrategy: 'Retry']

    }

    def testErrorStrategy2() {

        when:
        def config = new TaskConfig([:])
        config.errorStrategy( value )
        then:
        config.errorStrategy == expect
        config.getErrorStrategy() == expect

        where:
        expect                      | value
        null                        | null
        ErrorStrategy.TERMINATE     | 'terminate'
        ErrorStrategy.TERMINATE     | 'TERMINATE'
        ErrorStrategy.IGNORE        | 'ignore'
        ErrorStrategy.IGNORE        | 'Ignore'
        ErrorStrategy.RETRY         | 'retry'
        ErrorStrategy.RETRY         | 'Retry'

    }

    def testModules() {

        when:
        def config = new TaskConfig([:])
        config.module 't_coffee/10'
        config.module( [ 'blast/2.2.1', 'clustalw/2'] )

        then:
        config.module == ['t_coffee/10','blast/2.2.1', 'clustalw/2']
        config.getModule() == ['t_coffee/10','blast/2.2.1', 'clustalw/2']

        when:
        config = new TaskConfig([:])
        config.module 'a/1'
        config.module 'b/2:c/3'

        then:
        config.module == ['a/1','b/2','c/3']

        when:
        config = new TaskConfig([:])
        config.module = 'b/2:c/3'

        then:
        // I don't like this because it's hot uniform with the above getter method
        // TODO make it return a list as the getter
        config.module == 'b/2:c/3'
        config.getModule() == ['b/2','c/3']

    }

    def testShell() {

        when:
        def config = new TaskConfig([:])
        config.shell(value)
        then:
        config.shell == expect
        config.getShell() == expect

        where:
        expect               | value
        ['/bin/bash', '-ue'] | null
        ['/bin/bash', '-ue'] | []
        ['/bin/bash', '-ue'] | ''
        ['bash']             | 'bash'
        ['bash']             | ['bash']
        ['bash', '-e']       | ['bash', '-e']
        ['zsh', '-x']        | ['zsh', '-x']
    }


}
