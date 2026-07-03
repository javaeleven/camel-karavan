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

import {ClipboardCopy, Content, Flex, FlexItem, Label, Tooltip,} from '@patternfly/react-core';
import CodeBranchIcon from "@patternfly/react-icons/dist/esm/icons/code-branch-icon";
import {useFileStore, useProjectStore} from "@stores/ProjectStore";
import {shallow} from "zustand/shallow";

export function ProjectTitle() {

    const [project, tabIndex, setTabIndex] = useProjectStore((s) => [s.project, s.tabIndex, s.setTabIndex], shallow);
    const [file, setFile, operation] = useFileStore((s) => [s.file, s.setFile, s.operation], shallow);

    const isFile = file !== undefined && operation !== 'delete';
    const isLog = file !== undefined && file.name.endsWith("log");
    const filename = file ? file.name.substring(0, file.name.lastIndexOf('.')) : "";

    function getProjectTitle() {
        return (
            <Flex direction={{default: "column"}} gap={{default: 'gapNone'}}>
                <FlexItem>
                    <Content component="h3">{project?.name}</Content>
                </FlexItem>
                <FlexItem>
                    <ClipboardCopy hoverTip="Copy" clickTip="Copied" variant="inline-compact">
                        {project?.projectId}
                    </ClipboardCopy>
                </FlexItem>
                {project?.gitRepository &&
                    <FlexItem>
                        <Tooltip content={project.gitRepository}>
                            <Label color="blue" isCompact icon={<CodeBranchIcon/>}>
                                {project.gitBranch || 'default'}
                            </Label>
                        </Tooltip>
                    </FlexItem>
                }
            </Flex>
        )
    }

    function getFileTitle() {
        return (isFile ?
                <Flex direction={{default: "column"}} gap={{default: 'gapNone'}}>
                    <FlexItem>
                        <Content component="h3">{project?.name}</Content>
                    </FlexItem>
                    <FlexItem>
                        <ClipboardCopy hoverTip="Copy" clickTip="Copied" variant="inline-compact">
                            {isLog ? filename : file.name}
                        </ClipboardCopy>
                    </FlexItem>
                </Flex>
                : <></>
        )
    }


    return (
        <div className="dsl-title project-title">
            {isFile && getFileTitle()}
            {!isFile && getProjectTitle()}
        </div>
    )
}
