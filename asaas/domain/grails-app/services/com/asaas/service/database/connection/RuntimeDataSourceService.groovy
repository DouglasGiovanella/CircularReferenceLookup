package com.asaas.service.database.connection

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.log.AsaasLogger
import grails.spring.BeanBuilder
import groovy.sql.Sql
import org.springframework.beans.factory.BeanCreationException
import org.springframework.context.support.GenericApplicationContext

import javax.sql.DataSource
/**
 * Classe replicada do plugin [runtime-datasources:0.2](https://github.com/donalmurtagh/runtime-datasources/)
 */
class RuntimeDataSourceService {

    static transactional = false

    /**
     * Add a datasource by registering a bean with the Spring application context
     *
     * @param beanName the name of the Spring bean that will be registered for this datasource
     * @param dataSourceBeanImpl Implementation class of the Spring bean. If omitted
     * <tt>org.apache.tomcat.jdbc.pool.DataSource</tt> will be used by default
     * @param dataSourceProperties defines the properties of this datasource. At a minimum, the following should
     * be provided
     *
     * <pre>
     * <code>
     * {
     *     driverClassName = 'com.mysql.jdbc.Driver'
     *     url = 'jdbc:mysql://localhost/example'
     *     username = 'root'
     *     password = 'password'
     * }
     * </code>
     * </pre>
     *
     * This closure supports the same properties as the closure that is used to set datasource properties
     * at compile-time in <tt>DataSource.groovy</tt>
     *
     * @return the datasource
     */
    DataSource addDataSource(String beanName, Class<? extends DataSource> dataSourceBeanImpl, Closure dataSourceProperties) {
        if (AsaasApplicationHolder.applicationContext.containsBean(beanName)) {
            throw new BeanCreationException(beanName, "A Spring bean named '$beanName' already exists")
        }

        def beanBuilder = new BeanBuilder()

        beanBuilder.beans {
            "$beanName" dataSourceBeanImpl, dataSourceProperties
        }
        beanBuilder.registerBeans(AsaasApplicationHolder.applicationContext)
        AsaasLogger.info("Added datasource Spring bean named '$beanName' of type: $dataSourceBeanImpl.name")

        return AsaasApplicationHolder.applicationContext.getBean(beanName, DataSource)
    }

    /**
     * Remove a datasource
     * @param beanName the name of the datasource's Spring bean, this should match the name that was used
     * when the datasource was registered
     * @return a boolean indicating if removal of the datasource succeeded or not
     */
    boolean removeDataSource(String beanName) {
        if (AsaasApplicationHolder.applicationContext.containsBean(beanName)) {
            def bean = AsaasApplicationHolder.applicationContext.getBean(beanName, DataSource)

            if (bean instanceof DataSource) {
                (AsaasApplicationHolder.applicationContext as GenericApplicationContext).removeBeanDefinition(beanName)
                AsaasLogger.info("Removed datasource bean named '$beanName'")
                return true
            }
        } else {
            AsaasLogger.error("A datasource bean named '$beanName' could not be found")
            return false
        }
    }

    /**
     * Provides a Sql object that may be used to execute SQL statements against a datasource. The caller is
     * responsible for calling <tt>close()</tt> on this object when it is no longer needed.
     *
     * @param beanName the name of the datasource's Spring bean, this should match the name that was used
     * when the datasource was registered
     * @return
     */
    Sql getSql(String beanName) {
        DataSource dataSourceBean = AsaasApplicationHolder.applicationContext.getBean(beanName, DataSource)
        return new Sql(dataSourceBean)
    }

    /**
     * Provides a Sql object that may be used to execute SQL statements against a datasource.
     *
     * @param beanName the name of the datasource's Spring bean, this should match the name that was used
     * when the datasource was registered
     * @param sqlWork the <tt>Sql</tt> instance is passed as the one and only argument to this closure
     * @return whatever <tt>sqlWork</tt> returns
     */
    def doWithSql(String beanName, Closure sqlWork) {
        Sql sql = getSql(beanName)

        try {
            sqlWork(sql)
        } finally {
            sql.close()
        }
    }
}
