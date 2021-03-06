package graphql

import graphql.execution.DataFetcherResult
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.TypeRuntimeWiring
import spock.lang.Specification

/**
 * Tests to make sure that the local context from a root node can be propagated to the children and grandchildren.
 *
 * See <a href="https://github.com/graphql-java/graphql-java/issues/1635">#1635</a> for the original bug report.
 */
class LocalContextAwareCascadingTest extends Specification {

    // Configuration for the datafetchers for this schema, this will build a local context which will be consumed all the way down to the user
    // of the comments
    def graphql = TestUtil.graphQL("""
                type Query {
                    blog: Blog!
                }
                
                type Blog {
                    name: String!
                    comments: [Comment!]!
                }
                
                type Comment {
                    author: User!
                    title: String!
                    body: String!
                    
                }
                
                type User {
                    username: String!
                }
            """,
            RuntimeWiring.newRuntimeWiring()
                    .type(TypeRuntimeWiring.newTypeWiring("Query")
                            .dataFetcher("blog", {
                                def blog = new Blog()
                                blog.name = "some name"
                                DataFetcherResult.newResult()
                                        .data(blog)
                                        .localContext("BLOG CONTEXT") // here is where we build the initial context
                                        .build()
                            })
                    )
                    .type(TypeRuntimeWiring.newTypeWiring("Blog")
                            .dataFetcher("comments", {
                                def comment = new Comment()
                                comment.title = it.getLocalContext() + " (comments data fetcher)"
                                DataFetcherResult.newResult()
                                        .data([comment])
                                        .build()
                            })
                    )
                    .type(TypeRuntimeWiring.newTypeWiring("Comment")
                            .dataFetcher("body", { it.getLocalContext() + " (comment data fetcher)" })
                            .dataFetcher("author", { new User() })
                    )
                    .type(TypeRuntimeWiring.newTypeWiring("User")
                            .dataFetcher("username", { it.getLocalContext() + " (user data fetcher)" })
                    )
                    .build())
            .build()

    def "when a local context is provided, it can be consumed by the grandchildren node data fetchers"() {
        given:
            def input = ExecutionInput.newExecutionInput()
                    .query('''
                            query {
                                blog {
                                    name
                                    comments {
                                        title
                                        body
                                        author {
                                            username
                                        }
                                    }
                                }
                            }
                            ''')
                    .build()
        when:
            def executionResult = graphql.execute(input)

        then:
            executionResult.errors.isEmpty()
            executionResult.data == [
                    blog: [
                            name    : "some name",
                            comments: [
                                    [
                                            title: "BLOG CONTEXT (comments data fetcher)",
                                            body : "BLOG CONTEXT (comment data fetcher)",
                                            author: [
                                                    username: "BLOG CONTEXT (user data fetcher)"
                                            ]
                                    ]
                            ]
                    ]
            ]
    }

    // Really simply beans, without getters and setters for simplicity
    static class User {
        public String username
    }

    static class Comment {
        String title
        String body
        User author
    }

    static class Blog {
        String name
        List<Comment> comments
    }
}
