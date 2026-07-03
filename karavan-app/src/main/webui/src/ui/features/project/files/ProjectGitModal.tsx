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
import {useEffect, useRef, useState} from 'react';
import {
    Alert,
    Button,
    Form,
    FormAlert,
    FormGroup,
    FormHelperText,
    FormSelect,
    FormSelectOption,
    HelperText,
    HelperTextItem,
    InputGroup,
    InputGroupItem,
    Modal,
    ModalBody,
    ModalFooter,
    ModalHeader,
    ModalVariant,
    TextInput
} from '@patternfly/react-core';
import {Project} from "@models/ProjectModels";
import {KaravanApi} from "@api/KaravanApi";
import {EventBus} from "@features/project/designer/utils/EventBus";
import {useProjectStore} from "@stores/ProjectStore";

interface Props {
    project: Project;
    isOpen: boolean;
    onClose: () => void;
}

/**
 * Configure a project's own Git remote after creation: enter a repository URL,
 * Fetch its branches (authenticated with the current user's Git credentials from
 * System settings), pick a branch, and save. Clearing the URL detaches the remote
 * (the project becomes local-only — there is no global repository).
 */
export function ProjectGitModal({project, isOpen, onClose}: Props) {

    const setProject = useProjectStore((s) => s.setProject);
    const [gitRepository, setGitRepository] = useState<string>('');
    const [gitBranch, setGitBranch] = useState<string>('');
    const [branches, setBranches] = useState<string[]>([]);
    const [fetching, setFetching] = useState<boolean>(false);
    const [saving, setSaving] = useState<boolean>(false);
    const [fetchError, setFetchError] = useState<string>();
    const latestRepoRef = useRef<string>('');
    const wasOpenRef = useRef<boolean>(false);

    useEffect(() => {
        // Reset only on the closed->open transition, so a background project
        // refresh (new store object) while editing does not clobber unsaved input.
        if (isOpen && !wasOpenRef.current) {
            setGitRepository(project.gitRepository ?? '');
            setGitBranch(project.gitBranch ?? '');
            setBranches(project.gitBranch ? [project.gitBranch] : []);
            setFetchError(undefined);
            latestRepoRef.current = (project.gitRepository ?? '').trim();
        }
        wasOpenRef.current = isOpen;
    }, [isOpen, project]);

    function fetchBranches() {
        const requested = gitRepository.trim();
        latestRepoRef.current = requested;
        setFetching(true);
        setFetchError(undefined);
        KaravanApi.fetchBranches(requested, (res) => {
            if (latestRepoRef.current !== requested) {
                return;
            }
            setFetching(false);
            if (res.status === 200) {
                const list: string[] = res.data?.branches ?? [];
                setBranches(list);
                if (list.length > 0 && !list.includes(gitBranch)) {
                    setGitBranch(list[0]);
                }
            } else {
                setBranches([]);
                setFetchError(res?.response?.data || 'Unable to fetch branches');
            }
        });
    }

    function save() {
        const repo = gitRepository.trim() || undefined;
        const branch = repo ? (gitBranch || undefined) : undefined;
        setSaving(true);
        KaravanApi.updateProjectGit(project.projectId, repo, branch, (res) => {
            setSaving(false);
            if (res.status === 200) {
                setProject(new Project(res.data), 'select');
                EventBus.sendAlert('Success', 'Project Git remote updated', "success");
                onClose();
            } else {
                EventBus.sendAlert('Error', res?.response?.data || 'Failed to update Git remote', "danger");
            }
        });
    }

    return (
        <Modal variant={ModalVariant.small} isOpen={isOpen} onClose={onClose}>
            <ModalHeader title="Project Git remote"/>
            <ModalBody>
                <Form>
                    <FormGroup label="Git repository" fieldId="gitRepository">
                        <InputGroup>
                            <InputGroupItem isFill>
                                <TextInput id="gitRepository" type="text"
                                           placeholder="https://github.com/org/repo.git"
                                           autoComplete="off"
                                           value={gitRepository}
                                           onChange={(_e, v) => {
                                               latestRepoRef.current = v.trim();
                                               setGitRepository(v);
                                               setBranches([]);
                                               setGitBranch('');
                                               setFetchError(undefined);
                                           }}/>
                            </InputGroupItem>
                            <InputGroupItem>
                                <Button variant="secondary" isLoading={fetching}
                                        isDisabled={fetching || gitRepository.trim().length === 0}
                                        onClick={fetchBranches}>Fetch</Button>
                            </InputGroupItem>
                        </InputGroup>
                        <FormHelperText>
                            <HelperText>
                                <HelperTextItem>Leave empty to use the default repository. Credentials come from System → Git.</HelperTextItem>
                            </HelperText>
                        </FormHelperText>
                    </FormGroup>
                    {branches.length > 0 &&
                        <FormGroup label="Branch" fieldId="gitBranch">
                            <FormSelect id="gitBranch" value={gitBranch} onChange={(_e, v) => setGitBranch(v)}>
                                {branches.map((b) => (<FormSelectOption key={b} value={b} label={b}/>))}
                            </FormSelect>
                        </FormGroup>
                    }
                    {fetchError &&
                        <FormAlert>
                            <Alert variant="warning" title={fetchError} aria-live="polite" isInline/>
                        </FormAlert>
                    }
                </Form>
            </ModalBody>
            <ModalFooter>
                <Button key="confirm" variant="primary" onClick={save} isLoading={saving} isDisabled={saving}>Save</Button>
                <Button key="cancel" variant="secondary" onClick={onClose}>Cancel</Button>
            </ModalFooter>
        </Modal>
    )
}
