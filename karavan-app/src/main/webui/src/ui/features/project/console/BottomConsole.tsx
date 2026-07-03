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
import {JSX, ReactNode, useContext, useEffect, useState} from 'react';
import {Button, ToggleGroup, ToggleGroupItem, Tooltip, TooltipPosition} from '@patternfly/react-core';
import AngleUpIcon from '@patternfly/react-icons/dist/esm/icons/angle-up-icon';
import AngleDownIcon from '@patternfly/react-icons/dist/esm/icons/angle-down-icon';
import {shallow} from 'zustand/shallow';
import {useProjectStore, useSelectedContainerStore} from '@stores/ProjectStore';
import {BUILD_IN_PROJECTS} from '@models/ProjectModels';
import {ProjectContainersContext} from '@features/project/ProjectContainersContextProvider';
import {ContainerLogTab} from '@features/project/ContainerLogTab';
import {DevModeToolbar} from '@features/project/toolbar/DevModeToolbar';
import {BuildToolbar} from '@features/project/toolbar/BuildToolbar';
import './BottomConsole.css';

type ConsoleTab = 'execution' | 'build';

/**
 * Compact, collapsible console pinned to the bottom of the project / route-designer
 * panel. It replaces the old top-navbar "Log" / "Build" runtime tabs (which yanked
 * the user out of the designer into a full-screen log) with a bottom drawer that
 * keeps the designer visible while showing the dev (execution) and build logs and
 * their run/build actions.
 */
export function BottomConsole(): JSX.Element | null {

    const context = useContext(ProjectContainersContext);
    const [project] = useProjectStore((s) => [s.project], shallow);
    const setSelectedContainerName = useSelectedContainerStore((s) => s.setSelectedContainerName);

    const [expanded, setExpanded] = useState<boolean>(false);
    const [activeTab, setActiveTab] = useState<ConsoleTab>('execution');

    const isBuildIn = BUILD_IN_PROJECTS.includes(project?.projectId);

    const containerStatuses = context?.containerStatuses ?? [];
    const devModeIsRunning = context?.devModeIsRunning ?? false;
    const buildIsRunning = context?.buildIsRunning ?? false;
    const devContainer = containerStatuses.find(c => c.type === 'devmode');
    const buildContainer = containerStatuses.find(c => c.type === 'build');
    const targetContainer = activeTab === 'build' ? buildContainer : devContainer;
    const targetContainerName = targetContainer?.containerName;

    // The drawer owns the selected container: it follows the active console tab so
    // the log viewer streams the right container (devmode for Execution, build for Build).
    useEffect(() => {
        setSelectedContainerName(targetContainerName);
    }, [targetContainerName, activeTab, setSelectedContainerName]);

    // Pop the drawer open (on the matching tab) when a run or build starts, so the
    // user sees logs without leaving the designer.
    useEffect(() => {
        if (devModeIsRunning) {
            setActiveTab('execution');
            setExpanded(true);
        }
    }, [devModeIsRunning]);
    useEffect(() => {
        if (buildIsRunning) {
            setActiveTab('build');
            setExpanded(true);
        }
    }, [buildIsRunning]);

    if (isBuildIn) {
        return null;
    }

    function selectTab(tab: ConsoleTab) {
        setActiveTab(tab);
        setExpanded(true);
    }

    function statusDot(): JSX.Element | null {
        if (devModeIsRunning) {
            return <Tooltip content="Dev mode running" position={TooltipPosition.top}>
                <span className="console-status is-run" aria-label="Dev mode running"/>
            </Tooltip>;
        }
        if (buildIsRunning) {
            return <Tooltip content="Building" position={TooltipPosition.top}>
                <span className="console-status is-build" aria-label="Building"/>
            </Tooltip>;
        }
        return null;
    }

    // The single merged strip. When expanded it IS the log viewer's toolbar (so the
    // search box stays inside the LogViewer and the run/build actions + log controls
    // share one dense row); when collapsed it stands alone as the panel handle.
    function consoleBar(logControls?: ReactNode): JSX.Element {
        return (
            <div className="console-bar">
                <ToggleGroup aria-label="Console logs" isCompact className="console-tabs">
                    <ToggleGroupItem text="Execution" buttonId="execution"
                                     isSelected={activeTab === 'execution'} onChange={() => selectTab('execution')}/>
                    <ToggleGroupItem text="Build" buttonId="build"
                                     isSelected={activeTab === 'build'} onChange={() => selectTab('build')}/>
                </ToggleGroup>
                {statusDot()}
                {/* tabs/status sit left; actions + log controls are pushed right. */}
                <span className="console-grow"/>
                <div className="console-actions">
                    {activeTab === 'execution' ? <DevModeToolbar/> : <BuildToolbar/>}
                </div>
                <span className="console-sep" aria-hidden="true"/>
                {logControls}
                <Tooltip content={expanded ? 'Collapse panel' : 'Expand panel'} position={TooltipPosition.top}>
                    <Button variant="plain" aria-label="Toggle panel" className="console-toggle"
                            icon={expanded ? <AngleDownIcon/> : <AngleUpIcon/>}
                            onClick={() => setExpanded(!expanded)}/>
                </Tooltip>
            </div>
        );
    }

    return (
        <div className={`bottom-console ${expanded ? 'expanded' : 'collapsed'}`}>
            {expanded
                ? <div className="bottom-console-body">
                    <ContainerLogTab compact renderToolbar={consoleBar}/>
                </div>
                : consoleBar()
            }
        </div>
    );
}
