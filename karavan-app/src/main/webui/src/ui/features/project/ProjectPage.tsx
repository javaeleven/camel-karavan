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
import {JSX, useEffect, useState} from 'react';
import './ProjectPage.css';
import {shallow} from "zustand/shallow";
import {useNavigate, useParams} from "react-router-dom";
import {useAppConfigStore, useFilesStore, useFileStore, useProjectsStore, useProjectStore} from '@stores/ProjectStore';
import {BUILD_IN_PROJECTS, Project, ProjectType} from '@models/ProjectModels';
import {RightPanel} from "@shared/ui/RightPanel";
import {ROUTES} from "@app/navigation/Routes";
import {useProjectFunctions} from './ProjectContext';
import {ProjectPageNavigation} from "@features/project/ProjectPageNavigation";
import {ProjectContainersContextProvider} from "@features/project/ProjectContainersContextProvider";
import {Content} from "@patternfly/react-core";
import {ProjectToolbar} from "@features/project/toolbar/ProjectToolbar";
import {ProjectPanel} from "@features/project/ProjectPanel";
import {BottomConsole} from "@features/project/console/BottomConsole";
import {useDataPolling} from "@shared/polling/useDataPolling";
import {useProjectPageStore} from "@features/project/ProjectPageStore";
import {ErrorBoundaryWrapper} from "@shared/ui/ErrorBoundaryWrapper";
import {useDesignerStore} from "@features/project/designer/DesignerStore";
import {ProjectService} from "@services/ProjectService";

interface ProjectPageProps {
    developerManager: JSX.Element;
}

export function ProjectPage(props: ProjectPageProps): JSX.Element {

    const {developerManager} = props;
    const {showSideBar, setShowSideBar} = useProjectPageStore();
    const [files] = useFilesStore((s) => [s.files], shallow);
    const [projects] = useProjectsStore((state) => [state.projects], shallow)
    const [project, setProject, tabIndex, setTabIndex, refreshTrace] =
        useProjectStore((s) => [s.project, s.setProject, s.tabIndex, s.setTabIndex, s.refreshTrace], shallow);
    const [file, operation, setFile] = useFileStore((s) => [s.file, s.operation, s.setFile], shallow);
    const showFilePanel = file !== undefined && operation === 'select';
    const [urlFileName, setUrlFileName] = useState<string>();
    const {refreshData} = useProjectFunctions();
    const [isDebugging] = useDesignerStore((s) => [s.isDebugging], shallow);
    const [config] = useAppConfigStore((s) => [s.config], shallow);

    useDataPolling('ProjectPage', refreshData, 3000, [tabIndex, refreshTrace, project]);

    // Dedicated fast poll for the interactive debugger's suspended-exchange state.
    // Runs only while isDebugging is true; a no-op otherwise.
    useDataPolling('debug', () => {
        if (isDebugging && project?.projectId) {
            ProjectService.refreshDebugState(project.projectId, config.environment);
        }
    }, 1000, [isDebugging, project, config.environment]);

    let {projectId, fileName} = useParams();
    const navigate = useNavigate();

    useEffect(() => {
        setUrlFileName(fileName)
        window.history.replaceState({}, "", `${ROUTES.PROJECTS}/${projectId}`);
        return () => {
            setProject(new Project(), "none");
            setTabIndex('architecture');
        }
    }, []);

    // Resolve the project from the URL id. On a page reload the projects store is
    // still empty (data loads after auth), so WAIT for it — otherwise we'd bounce to
    // the projects list on every reload. Only (re)select when the id actually changes
    // so the 3s data refresh doesn't reset the open tab.
    useEffect(() => {
        if (projects.length === 0) return;
        const p = projects.find(project => project.projectId === projectId);
        if (!p) {
            navigate(ROUTES.PROJECTS);
            return;
        }
        if (useProjectStore.getState().project?.projectId !== projectId) {
            setProject(p, "select");
            if (!BUILD_IN_PROJECTS.includes(p.projectId)) {
                setTabIndex('architecture');
            }
        }
    }, [projects, projectId]);

    useEffect(() => {
        if (urlFileName !== undefined) {
            const file = files
                .find(f => f.projectId === projectId && f.name === urlFileName);
            if (file) {
                setFile('select', file);
                setTabIndex('architecture');
                setUrlFileName(undefined);
            }
        }
    }, [files]);

    function title() {
        return (<Content component="h2">Integration</Content>)
    }

    const isBuildIn = BUILD_IN_PROJECTS.includes(project?.projectId);
    const showConsole = !isBuildIn && project?.type === ProjectType.integration;

    return (
        <RightPanel
            title={title()}
            toolsStart={
                <ProjectContainersContextProvider>
                    <ProjectPageNavigation/>
                </ProjectContainersContextProvider>
            }
            tools={<ProjectToolbar/>}
            mainPanel={
                <div className="right-panel-card">
                    <div className="project-main-content">
                        <ErrorBoundaryWrapper onError={error => console.error(error)}>
                            {showFilePanel && developerManager}
                            {!showFilePanel && <ProjectPanel/>}
                        </ErrorBoundaryWrapper>
                    </div>
                    {showConsole &&
                        <ProjectContainersContextProvider>
                            <BottomConsole/>
                        </ProjectContainersContextProvider>
                    }
                </div>
            }
        />
    )
}