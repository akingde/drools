/*
 * Copyright 2011 Red Hat Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.drools.persistence.session;

import static org.drools.persistence.util.PersistenceUtil.*;
import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Random;

import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;

import org.drools.KnowledgeBase;
import org.drools.KnowledgeBaseFactory;
import org.drools.base.MapGlobalResolver;
import org.drools.builder.KnowledgeBuilder;
import org.drools.builder.KnowledgeBuilderFactory;
import org.drools.builder.ResourceType;
import org.drools.common.DefaultFactHandle;
import org.drools.definition.KnowledgePackage;
import org.drools.io.ResourceFactory;
import org.drools.persistence.PersistenceContextManager;
import org.drools.persistence.info.SessionInfo;
import org.drools.persistence.jpa.JPAKnowledgeService;
import org.drools.persistence.jpa.JpaPersistenceContextManager;
import org.drools.runtime.Environment;
import org.drools.runtime.EnvironmentName;
import org.drools.runtime.StatefulKnowledgeSession;
import org.drools.runtime.rule.FactHandle;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import bitronix.tm.TransactionManagerServices;
import bitronix.tm.resource.jdbc.PoolingDataSource;

public class ReloadSessionTest {

    // Datasource (setup & clean up)
    private PoolingDataSource ds1;
    private EntityManagerFactory emf;

    private static String simpleRule = "package org.drools.test\n"
            + "global java.util.List list\n" 
            + "rule rule1\n" 
            + "when\n"
            + "  Integer(intValue > 0)\n" 
            + "then\n" 
            + "  list.add( 1 );\n"
            + "end\n" 
            + "\n";

    @Before
    public void setup() {
        // Initialize datasource and global settings
        ds1 = setupPoolingDataSource();
        ds1.init();
        
        emf = Persistence.createEntityManagerFactory(DROOLS_PERSISTENCE_UNIT_NAME);
    }

    @After
    public void cleanUp() {
        emf.close();
        ds1.close();
    }

    private KnowledgeBase initializeKnowledgeBase(String rule) { 
        // Initialize knowledge base/session/etc..
        KnowledgeBuilder kbuilder = KnowledgeBuilderFactory.newKnowledgeBuilder();
        kbuilder.add( ResourceFactory.newByteArrayResource(rule.getBytes()), ResourceType.DRL);

        if (kbuilder.hasErrors()) {
            fail(kbuilder.getErrors().toString());
        }

        KnowledgeBase kbase = KnowledgeBaseFactory.newKnowledgeBase();
        kbase.addKnowledgePackages(kbuilder.getKnowledgePackages());
       
        return kbase;
    }
    
    private Environment initializeEnvironment(EntityManagerFactory emf) {
        Environment env = KnowledgeBaseFactory.newEnvironment();
        env.set(EnvironmentName.ENTITY_MANAGER_FACTORY, emf);
        env.set(EnvironmentName.GLOBALS, new MapGlobalResolver());
        env.set(EnvironmentName.TRANSACTION_MANAGER,
                TransactionManagerServices.getTransactionManager());
        
        return env;
    }

    @Test 
    public void reloadKnowledgeSessionTest() { 
        
        // Initialize drools environment stuff
        Environment env = initializeEnvironment(emf);
        KnowledgeBase kbase = initializeKnowledgeBase(simpleRule);
        StatefulKnowledgeSession commandKSession = JPAKnowledgeService.newStatefulKnowledgeSession( kbase, null, env );
        assertTrue("There should be NO facts present in a new (empty) knowledge session.", commandKSession.getFactHandles().isEmpty());
        
        // Persist a facthandle to the database
        Integer integerFact = (new Random()).nextInt(Integer.MAX_VALUE-1) + 1;
        commandKSession.insert( integerFact );
       
        // At this point in the code, the fact has been persisted to the database
        //  (within a transaction via the SingleSessionCommandService) 
        Collection<FactHandle> factHandles =  commandKSession.getFactHandles();
        assertTrue("At least one fact should have been inserted by the ksession.insert() method above.", !factHandles.isEmpty());
        FactHandle origFactHandle = factHandles.iterator().next();
        assertTrue("The stored fact should contain the same number as the value inserted (but does not).", 
                Integer.parseInt(((DefaultFactHandle) origFactHandle).getObject().toString()) == integerFact.intValue() );
        
        // Save the sessionInfo id in order to retrieve it later
        int sessionInfoId = commandKSession.getId();
        
        // Clean up the session, environment, etc.
        PersistenceContextManager pcm = (PersistenceContextManager) commandKSession.getEnvironment().get(EnvironmentName.PERSISTENCE_CONTEXT_MANAGER);
        commandKSession.dispose();
        pcm.dispose();
        emf.close();
     
        // Reload session from the database
        emf = Persistence.createEntityManagerFactory(DROOLS_PERSISTENCE_UNIT_NAME);
        env = initializeEnvironment(emf);
       
        // Re-initialize the knowledge session _and knowledge base_: 
        //  - a KnowledgeBase instantiation (can) contain a hierarchy of references 
        //    that eventually references an object store 
        //    This means it _must_ be reinitialized when reloading the persistence context.
        KnowledgeBase newkbase = initializeKnowledgeBase(simpleRule);
        StatefulKnowledgeSession newCommandKSession 
            = JPAKnowledgeService.loadStatefulKnowledgeSession(sessionInfoId, newkbase, null, env);
       
        // Test that the session has been successfully reinitialized
        factHandles =  newCommandKSession.getFactHandles();
        assertTrue("At least one fact should have been persisted by the ksession.insert above.", 
                !factHandles.isEmpty() && factHandles.size() == 1);
        FactHandle retrievedFactHandle = factHandles.iterator().next();
        assertTrue("If the retrieved and original FactHandle object are the same, then the knowledge session has NOT been reloaded!", 
                origFactHandle != retrievedFactHandle);
        assertTrue("The retrieved fact should contain the same info as the original (but does not).", 
                Integer.parseInt(((DefaultFactHandle) retrievedFactHandle).getObject().toString()) == integerFact.intValue() );
        
        // Test to see if the (retrieved) facts can be processed
        ArrayList<Object>list = new ArrayList<Object>();
        newCommandKSession.setGlobal( "list", list );
        newCommandKSession.fireAllRules();
        assertEquals( 1, list.size() );
    }

}
