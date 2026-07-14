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
import React, {useCallback, useEffect, useRef} from 'react';
import {Button,} from '@patternfly/react-core';
import './RouteDesigner.css';
import {DslSelector} from "../selector/DslSelector";
import {DslConnections} from "./DslConnections";
import PlusIcon from "@patternfly/react-icons/dist/esm/icons/plus-icon";
import {DslElement} from "./element/DslElement";
import {CamelUi} from "../utils/CamelUi";
import {useRouteDesignerHook} from "./useRouteDesignerHook";
import {useConnectionsStore, useDesignerStore, useIntegrationStore, useSelectorStore} from "../DesignerStore";
import {shallow} from "zustand/shallow";
import useResizeObserver from "./useResizeObserver";
import {DeleteConfirmation} from "./DeleteConfirmation";
import {DslElementMoveModal} from "./element/DslElementMoveModal";
import {RouteTemplateElement} from "./element/RouteTemplateElement";

export function RouteDesigner() {

    const {openSelector, createRouteConfiguration, unselectElement, onDslSelect,
        isSourceKamelet, isActionKamelet, isKamelet, isSinkKamelet, createRouteTemplate} = useRouteDesignerHook();

    const [integration] = useIntegrationStore((state) => [state.integration], shallow)
    const [showDeleteConfirmation, setPosition, showMoveConfirmation, setShowMoveConfirmation, passedIds, isDebugging] =
        useDesignerStore((s) =>
        [s.showDeleteConfirmation, s.setPosition, s.showMoveConfirmation, s.setShowMoveConfirmation, s.passedNodeIds, s.isDebugging], shallow)

    const [showSelector] = useSelectorStore((s) => [s.showSelector], shallow)

    const [clearSteps] = useConnectionsStore((s) => [s.clearSteps], shallow)

    const [key, setKey] = React.useState<string | number>();

    const onChangeGraphSize = useCallback((target: HTMLDivElement)  => {
        changeGraphSize();
    }, [])

    function changeGraphSize ()  {
        if (flowRef && flowRef.current) {
            const el = flowRef.current;
            const rect = el.getBoundingClientRect();
            // Node positions are stored relative to the .flows container (see
            // DslElement.sendPosition), so the arrow-layer origin is fixed at (0,0)
            // and scrolling never changes coordinates. Only the graph SIZE matters,
            // so fire on size change only — scrolling then triggers zero re-renders.
            const s = useDesignerStore.getState();
            if (s.width !== rect.width || s.height !== rect.height || s.top !== 0 || s.left !== 0) {
                setPosition(rect.width, rect.height, 0, 0)
            }
        }
    }

    const firstRef = useResizeObserver(onChangeGraphSize);
    const printerRef = useRef<HTMLDivElement | null>(null);
    const flowRef = useRef<HTMLDivElement | null>(null);

    useEffect(() => {
        if (useDesignerStore.getState().selectedStep === undefined) {
            setKey(Math.random.toString())
        }
    }, []);

    useEffect(()=> {
        const interval = setInterval(() => {
            changeGraphSize();
        }, 300);
        try {
            if (flowRef.current === null) {
                clearSteps();
            } else {
                changeGraphSize();
            }
        } catch (error) {
            console.error(error);
        }
        return ()=> {
            clearInterval(interval)
        }
    }, [showSelector, integration])

    function getGraphButtons() {
        const routes = CamelUi.getRoutes(integration);
        const routeConfigurations = CamelUi.getRouteConfigurations(integration);
        const showNewRoute = routes.length === 0 && routeConfigurations?.length === 0;
        const showNewRouteTemplate = !isKamelet() && routes.length === 0 && routeConfigurations?.length === 0;
        const showNewRouteConfiguration = !isKamelet() && routes.length === 0;
        return (
            <div className="add-flow">
                {showNewRoute && <Button
                    variant={routes.length === 0 ? "primary" : "secondary"}
                    icon={<PlusIcon/>}
                    onClick={evt => {
                        evt.stopPropagation();
                        if (isSinkKamelet() || isActionKamelet()) {
                            const dsl = CamelUi.getDslMetaModel('FromDefinition');
                            dsl.uri = 'kamelet:source';
                            onDslSelect(dsl, '', undefined);
                        } else {
                            openSelector(undefined, undefined)
                        }
                    }}
                >
                    Create route
                </Button>}
                {showNewRouteConfiguration && <Button
                    variant="secondary"
                    icon={<PlusIcon/>}
                    onClick={e => createRouteConfiguration()}
                >
                    Create configuration
                </Button>}
                {showNewRouteTemplate && <Button
                    variant="secondary"
                    icon={<PlusIcon/>}
                    onClick={evt => {
                        evt.stopPropagation();
                        openSelector(undefined, undefined, undefined, undefined, true);
                    }}
                >
                    Create template
                </Button>}
            </div>
        )
    }
    function getGraph() {
        const routes = CamelUi.getRoutes(integration);
        const routeConfigurations = CamelUi.getRouteConfigurations(integration);
        const routeTemplates = CamelUi.getRouteTemplates(integration);
        return (
            <div className="graph" ref={printerRef}>
                <DslConnections/>
                <div id="flows"
                     className="flows"
                     data-click="FLOWS"
                     onClick={event => {unselectElement(event)}}
                     ref={flowRef}>
                    {routeConfigurations?.map((routeConfiguration, index: number, array) => (
                        <DslElement key={routeConfiguration.uuid}
                                    inSteps={false}
                                    position={index}
                                    step={routeConfiguration}
                                    nextStep={undefined}
                                    prevStep={undefined}
                                    inStepsLength={array.length}
                                    parent={undefined}/>
                    ))}
                    {routeTemplates?.map((routeTemplate, index: number, array) => (
                        <RouteTemplateElement key={routeTemplate.uuid}
                                    inSteps={false}
                                    position={index}
                                    step={routeTemplate}
                                    nextStep={undefined}
                                    prevStep={undefined}
                                    inStepsLength={array.length}
                                    parent={undefined}/>
                    ))}
                    {routes?.map((route: any, index: number, array) => {
                        return (
                            <DslElement key={route.uuid}
                                        inSteps={false}
                                        position={index}
                                        step={route}
                                        nextStep={undefined}
                                        prevStep={undefined}
                                        inStepsLength={array.length}
                                        parent={undefined}/>
                        )
                    })}
                    {!isDebugging && getGraphButtons()}
                    <div className="flows-end-spacer" aria-hidden="true"/>
                </div>
            </div>)
    }

    const hasFlows = integration?.spec?.flows !== undefined;
    return (
        <div className="dsl-page" ref={firstRef} key={key}>
            <div className="dsl-page-columns">
                {hasFlows && getGraph()}
            </div>
            {showSelector && <DslSelector onDslSelect={onDslSelect}/>}
            {showDeleteConfirmation && <DeleteConfirmation/>}
            {showMoveConfirmation && <DslElementMoveModal/>}
        </div>
    )
}