/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import {useEffect, useState} from 'react';
import {
    Badge,
    Button,
    Content,
    Label,
    Popover,
    TextInput,
    Tooltip,
    ValidatedOptions,
} from '@patternfly/react-core';
import {PlusCircleIcon, TimesIcon, HelpIcon} from '@patternfly/react-icons/dist/esm/icons';
import {v4 as uuidv4} from "uuid";
import {CodeUtils, JBANG_DEPENDENCIES_PROPERTY} from "@util/CodeUtils";
import './DependenciesPanel.css';

interface DependencyRow {
    id: string;
    value: string;
}

interface Props {
    code: string;
    onChange: (code: string) => void;
}

type DependencyKind = 'camel' | 'mvn' | 'github' | 'other';

function dependencyKind(value: string): DependencyKind {
    if (value.startsWith('camel:')) return 'camel';
    if (value.startsWith('mvn:')) return 'mvn';
    if (value.startsWith('github:')) return 'github';
    return 'other';
}

const KIND_COLORS: Record<DependencyKind, 'blue' | 'orange' | 'purple' | 'grey'> = {
    camel: 'blue',
    mvn: 'orange',
    github: 'purple',
    other: 'grey',
};

// camel:name | mvn:group:artifact:version[...] | github:user/repo[...] | group:artifact:version
const VALID_DEPENDENCY = new RegExp(
    '^(camel:[\\w.-]+' +
    '|mvn:[^:,\\s]+:[^:,\\s]+(:[^:,\\s]+)+' +
    '|github:\\S+' +
    '|[^:,\\s]+:[^:,\\s]+(:[^:,\\s]+)+)$'
);

/**
 * Form-based manager for the camel.jbang.dependencies property: every
 * dependency is an editable row (amend in place, delete), plus an add-new row —
 * same interaction model as bean property forms. Changes are written back into
 * the application.properties code (only that line is touched) and flow through
 * the regular file autosave; edits made in the code editor show up here live.
 */
export function DependenciesPanel(props: Props) {

    const {code, onChange} = props;
    const [rows, setRows] = useState<DependencyRow[]>([]);

    useEffect(() => {
        const parsed = CodeUtils.getJbangDependencies(code ?? '');
        const current = rows.map(r => r.value.trim()).filter(v => v.length > 0);
        // resync from code only on real external change; keeps in-progress
        // (still-empty) rows and cursor positions intact while typing
        if (JSON.stringify(parsed) !== JSON.stringify(current)) {
            setRows(parsed.map(v => ({id: uuidv4(), value: v})));
        }
    }, [code]);

    function applyRows(newRows: DependencyRow[]) {
        setRows(newRows);
        onChange(CodeUtils.setJbangDependencies(code ?? '', newRows.map(r => r.value)));
    }

    function rowChanged(id: string, value: string) {
        applyRows(rows.map(r => r.id === id ? {...r, value: value} : r));
    }

    function rowDeleted(id: string) {
        applyRows(rows.filter(r => r.id !== id));
    }

    function addRow() {
        setRows([...rows, {id: uuidv4(), value: ''}]);
    }

    const count = rows.filter(r => r.value.trim().length > 0).length;

    return (
        <div className="dependencies-panel">
            <div className="dependencies-panel-header">
                <Content component='h4'>Dependencies</Content>
                <Badge isRead>{count}</Badge>
                <Popover
                    position="left"
                    headerContent={JBANG_DEPENDENCIES_PROPERTY}
                    bodyContent={
                        <div>
                            <p>Extra libraries added to the integration at run/build time:</p>
                            <p><b>camel:</b>name — Camel component, e.g. <i>camel:aws-bedrock</i></p>
                            <p><b>mvn:</b>group:artifact:version — Maven artifact, e.g. <i>mvn:com.prowidesoftware:pw-iso20022:SRU2024-10.2.7</i></p>
                            <p><b>github:</b>user/repo — GitHub project</p>
                        </div>
                    }>
                    <Button variant="plain" aria-label="Dependency formats" className="help-button" icon={<HelpIcon/>}/>
                </Popover>
            </div>
            <div className="dependencies-panel-rows">
                {rows.map(row => {
                    const value = row.value.trim();
                    const kind = dependencyKind(value);
                    const valid = value.length === 0 || VALID_DEPENDENCY.test(value);
                    return (
                        <div key={row.id} className="dependency-row">
                            <Label color={KIND_COLORS[kind]} className="dependency-kind">{kind}</Label>
                            <TextInput
                                className="text-field"
                                type="text"
                                autoComplete="off"
                                aria-label={"dependency-" + row.id}
                                id={"dependency-" + row.id}
                                placeholder="camel:name or mvn:group:artifact:version"
                                value={row.value}
                                validated={valid ? ValidatedOptions.default : ValidatedOptions.warning}
                                onChange={(_, v) => rowChanged(row.id, v)}
                            />
                            <Tooltip content="Delete dependency" position="bottom-end">
                                <Button icon={<TimesIcon/>} isInline variant="link" className="delete-button"
                                        aria-label="delete-dependency"
                                        onClick={() => rowDeleted(row.id)}/>
                            </Tooltip>
                        </div>
                    )
                })}
                <Button icon={<PlusCircleIcon/>} isInline variant="link" className="add-button" onClick={addRow}>
                    Add dependency
                </Button>
            </div>
        </div>
    )
}
