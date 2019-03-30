package me.liuwj.ktorm.entity

import me.liuwj.ktorm.dsl.*
import me.liuwj.ktorm.expression.*
import me.liuwj.ktorm.schema.*
import kotlin.reflect.KClass

/**
 * 根据 ID 批量获取实体对象，会自动 left join 所有的引用表
 */
@Suppress("UNCHECKED_CAST")
fun <E : Entity<E>, K : Any> Table<E>.findMapByIds(ids: Collection<K>): Map<K, E> {
    return findListByIds(ids).associateBy { it.implementation.getPrimaryKeyValue(this) as K }
}

/**
 * 根据 ID 批量获取实体对象，会自动 left join 所有的引用表
 */
@Suppress("UNCHECKED_CAST")
fun <E : Entity<E>> Table<E>.findListByIds(ids: Collection<Any>): List<E> {
    if (ids.isEmpty()) {
        return emptyList()
    } else {
        val primaryKey = (this.primaryKey as? Column<Any>) ?: error("Table $tableName doesn't have a primary key.")
        return findList { primaryKey inList ids }
    }
}

/**
 * 根据 ID 获取对象，会自动 left join 所有的引用表
 */
@Suppress("UNCHECKED_CAST")
fun <E : Entity<E>> Table<E>.findById(id: Any): E? {
    val primaryKey = (this.primaryKey as? Column<Any>) ?: error("Table $tableName doesn't have a primary key.")
    return findOne { primaryKey eq id }
}

/**
 * 根据指定条件获取对象，会自动 left join 所有的引用表
 */
inline fun <E : Entity<E>, T : Table<E>> T.findOne(block: (T) -> ScalarExpression<Boolean>): E? {
    val list = findList(block)
    when (list.size) {
        0 -> return null
        1 -> return list[0]
        else -> throw IllegalStateException("Expected one result(or null) to be returned by findOne(), but found: ${list.size}")
    }
}

/**
 * 获取表中的所有记录，会自动 left join 所有的引用表
 */
fun <E : Entity<E>> Table<E>.findAll(): List<E> {
    return this
        .joinReferencesAndSelect()
        .map { row -> this.createEntity(row) }
}

/**
 * 根据指定条件获取对象列表，会自动 left join 所有的引用表
 */
inline fun <E : Entity<E>, T : Table<E>> T.findList(block: (T) -> ScalarExpression<Boolean>): List<E> {
    return this
        .joinReferencesAndSelect()
        .where { block(this) }
        .map { row -> this.createEntity(row) }
}

/**
 * 返回一个查询对象 [Query]，left join 所有引用表，select 所有字段
 */
fun Table<*>.joinReferencesAndSelect(): Query {
    val joinedTables = ArrayList<Table<*>>()

    return this
        .joinReferences(this.asExpression(), joinedTables)
        .select(joinedTables.flatMap { it.columns })
}

private fun Table<*>.joinReferences(
    expr: QuerySourceExpression,
    joinedTables: MutableList<Table<*>>
): QuerySourceExpression {

    var curr = expr

    joinedTables += this

    for (column in columns) {
        val binding = column.binding
        if (binding is ReferenceBinding) {
            val rightTable = binding.referenceTable
            val primaryKey = rightTable.primaryKey ?: error("Table ${rightTable.tableName} doesn't have a primary key.")

            curr = curr.leftJoin(rightTable, on = column eq primaryKey)
            curr = rightTable.joinReferences(curr, joinedTables)
        }
    }

    return curr
}

private infix fun ColumnDeclaring<*>.eq(column: ColumnDeclaring<*>): BinaryExpression<Boolean> {
    return BinaryExpression(BinaryExpressionType.EQUAL, asExpression(), column.asExpression(), BooleanSqlType)
}

/**
 * 从结果集中创建实体对象，会自动级联创建引用表的实体对象
 */
@Suppress("UNCHECKED_CAST")
fun <E : Entity<E>> Table<E>.createEntity(row: QueryRowSet): E {
    val entity = doCreateEntity(row, skipReferences = false) as E
    return entity.apply { discardChanges() }
}

/**
 * 从结果集中创建实体对象，不会自动级联创建引用表的实体对象
 */
@Suppress("UNCHECKED_CAST")
fun <E : Entity<E>> Table<E>.createEntityWithoutReferences(row: QueryRowSet): E {
    val entity = doCreateEntity(row, skipReferences = true) as E
    return entity.apply { discardChanges() }
}

private fun Table<*>.doCreateEntity(row: QueryRowSet, skipReferences: Boolean = false): Entity<*> {
    val entityClass = this.entityClass ?: error("No entity class configured for table: $tableName")
    val entity = Entity.create(entityClass, fromTable = this)

    for (column in columns) {
        try {
            row.retrieveColumn(column, intoEntity = entity, skipReferences = skipReferences)
        } catch (e: Throwable) {
            throw IllegalStateException("Error occur while retrieving column: $column, binding: ${column.binding}", e)
        }
    }

    return entity
}

private fun QueryRowSet.retrieveColumn(column: Column<*>, intoEntity: Entity<*>, skipReferences: Boolean) {
    val columnValue = (if (this.hasColumn(column)) this[column] else null) ?: return

    val binding = column.binding ?: return
    when (binding) {
        is ReferenceBinding -> {
            val rightTable = binding.referenceTable
            val primaryKey = rightTable.primaryKey ?: error("Table ${rightTable.tableName} doesn't have a primary key.")

            when {
                skipReferences -> {
                    val child = Entity.create(binding.onProperty.returnType.classifier as KClass<*>, fromTable = rightTable)
                    child.implementation.setColumnValue(primaryKey, columnValue)
                    intoEntity[binding.onProperty.name] = child.apply { discardChanges() }
                }
                this.hasColumn(primaryKey) && this[primaryKey] != null -> {
                    val child = rightTable.doCreateEntity(this)
                    child.implementation.setColumnValue(primaryKey, columnValue, forceSet = true)
                    intoEntity[binding.onProperty.name] = child.apply { discardChanges() }
                }
            }
        }
        is NestedBinding -> {
            var curr = intoEntity.implementation
            for ((i, prop) in binding.withIndex()) {
                if (i != binding.lastIndex) {
                    var child = curr.getProperty(prop.name) as Entity<*>?
                    if (child == null) {
                        child = Entity.create(prop.returnType.classifier as KClass<*>, parent = curr)
                        curr.setProperty(prop.name, child)
                    }

                    curr = child.implementation
                }
            }

            curr.setProperty(binding.last().name, columnValue)
        }
    }
}
